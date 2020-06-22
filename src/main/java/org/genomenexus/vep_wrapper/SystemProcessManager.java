package org.genomenexus.vep_wrapper;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

import org.springframework.beans.factory.annotation.Value;

/**
* Launches and destroys vep command line tool processes.
* Because vep is a perl process which forks child processes, the Java
* Process.destroy() eliminates the launched process but not any child
* processes, which are inherited by the parent process of the launched vep
* process after it is destroyed. On a full unix platform, these orphaned
* children should normally be cleaned up by the init process. However, in
* our Docker container deployment, the init process is not present and the
* parent process is the running JVM, which does not automatically clean up
* orphaned processes.
*
* By using this class to launch VEP processes and to (when needed)
* forcibly destroy launced VEP processes, this class is able to track the
* processes which have been launched and are expected to be running. These
* processes will be children of the JVM process, and by keeping track they
* can be distinguished from orphaned child processes of destroyed VEP
* processes which are now children of the JVM process.
*
* A Daemon thread is started at the time of the first launch of VEP. This
* thread periodically examines the processes running on the JVM and purges
* processes in the Zombie state which are children of the JVM process, and
* also will forcibly kill processes for which all of the following are
* true:
* - not tracked as being launched via SystemProcessManager
* - child of the JVM process (process 1)
* - in the Sleep state
* - launced using the primary command "perl"
* These rules should purge orphaned forked children of any failed or
* destroyed VEP processes. When using this code the application should be
* sure to launch any process using the command "perl" through this class
* only.
*
* Synchronization prevents concurrent execution of code which alters the
* list of tracked processes, or the Daemon thread's code which relies on
* that list during a cleanup operation. Additionally, there may be a short
* delay between calling Process.start() and being able to determine the
* process identifier (pid). The Daemon thread will wait a short time for
* the process identifier to be available. If unable to determine the
* process identifier, it will skip execution for one iteration time
* period.
*
* The cleanup thread is limited if it is determined that the system on
* which the code is deployed does not support unix style process
* management through commands 'ps', 'kill', and the java class
* java.lang.Process. If these capabilites are not present, the daemon
* thread will still remove launched VEP processes from the tracking list
* when they have finished processing and are no longer alive.
*/
public class SystemProcessManager implements Runnable {

    private static Set<Process> launchedProcesses = Collections.synchronizedSet(new HashSet<>());
    private static String UNIX_PROCESS_CLASS_NAME = "java.lang.UNIXProcess";
    public static final Long REAPER_DAEMON_WAIT_PERIOD = 2000L; // milliseconds
    public static final Long PID_PROBE_WAIT_PERIOD = 250L; // milliseconds
    public static final int JVM_PROCESS_ID = 1; // maybe this should be tested or determined at runtime
    public static final int PID_UNAVAILABLE = -1;
    private static Boolean processIdCanBeReadFromToStringMethod = null; // if available, this avoids reflection
    private static Boolean processIdCanBeReadFromUnixProcessField = null; // this method depends on an internal field
    private static Boolean psCommandAvailableOnSystem = null;
    private static Boolean killCommandAvailableOnSystem = null;
    private static Field processPidField = null;
    private static Method waitForProcessExitMethod = null;
    private static Pattern PID_REPORT_PATTERN = Pattern.compile("pid\\s*=\\s*(\\d+)"); // used to search toString output
    private static Process rememberedUnixProcess = null; // saved for calls to method waitForProcessExitMethod
    private static File devNull = null;
    private static Thread reaperDaemonThread = null;
    private static final Object reaperDaemonLock = new Object();
    private static Boolean reaperIsRunning = Boolean.FALSE;
    private static Boolean reaperShutdownRequested = Boolean.FALSE;

    static {
        devNull = new File("/dev/null"); // used to ignore process data streams
        try {
            devNull.exists();
        } catch (SecurityException e) {
            devNull = null;
        }
    }

    /**
    *   launch a new instance of the VEP command line tool.
    *
    *   by using this method, SystemProcessManager will be tracking the processes
    *   launched by the web service and will be able to avoid improper purging of
    *   these processes, which might otherwise appear to be orphaned child
    *   processes forked by other running VEP command line tool processes.
    *   @param pb A ProcessBuilder instance pre-loaded with the arguments for
    *               launching VEP command line tool.
    *   @return A started process, or null if an exception prevents the launch
    */
    public static Process launchVepProcess(ProcessBuilder pb) {
        // pb arguments are expected to use the vep command .. we could consider checking
        Process p = null;
        synchronized(reaperDaemonLock) {
            try {
                p = pb.start();
            } catch (IOException e) {
                return null;
            }
            if (p != null) {
                launchedProcesses.add(p);
                rememberedUnixProcess = p;
                startReaperDaemonThreadIfNeeded();
            }
        }
        return p;
    }

    /**
    *   shutdown a previously launched instance of the VEP command line tool.
    *
    *   the process is forcibly shutdown and removed from the tracked list of
    *   running vep processes
    *   @param p A previously launched process for the VEP command line tool.
    */
    public static void destroyVepProcess(Process p) {
        synchronized(reaperDaemonLock) {
            Process destroyedProcess = p.destroyForcibly();
            for (int maximumInterrupts = 1024; maximumInterrupts >= 0; maximumInterrupts--) {
                try {
                    destroyedProcess.waitFor();
                    break;
                } catch (InterruptedException e) {
                }
            }
            launchedProcesses.remove(p);
        }
    }

    /**
    *   The process reaper daemon thread.
    *
    *   This is more fully described in the class description. In brief:
    *   - exit if shutdown has been requested
    *   - short sleep period
    *   - notice and untrack launchedProcesses which completed on their own
    *   - detect and reap VEP zombie orphans
    *   - make sure all tracked launchedProcesses have accessible pid
    *   - detect and kill VEP orphans which have reached a sleep state
    */
    public void run() {
        reaperIsRunning = Boolean.TRUE;
        if (psCommandAvailableOnSystem == null) {
            determineIfPsCommandIsAvailable();
        }
        if (killCommandAvailableOnSystem == null) {
            determineIfKillCommandIsAvailable();
        }
        determineWaitForProcessExitMethod();
        determineAvailableMethodToReadPid();
        // ReaperDaemonThread
        reaping_loop : while (!reaperShutdownRequested) {
            attemptSleep(REAPER_DAEMON_WAIT_PERIOD); // sleep is done outside of synchronized region
            synchronized(reaperDaemonLock) {
                // purge launchedProcesses of completed processes
                Iterator<Process> iter = launchedProcesses.iterator();
                while (iter.hasNext()) {
                    Process p = iter.next();
                    if (!p.isAlive()) {
                        iter.remove();
                    }
                }
                // reap zombies if possible
                List<ProcessSurveyItem> processSurvey = null;
                if (psCommandAvailableOnSystem && waitForProcessExitMethod != null) {
                    processSurvey = ProcessSurveyor.getProcessSurvey();
                    for (ProcessSurveyItem psi : processSurvey) {
                        if (processIsZombie(psi) && waitForProcessExitMethod != null) {
                            try {
                                // reap
                                waitForProcessExitMethod.invoke(rememberedUnixProcess, psi.getProcessId());
                            } catch (IllegalAccessException e) {
                                waitForProcessExitMethod = null; // cannot use
                            } catch (InvocationTargetException e) {
                                waitForProcessExitMethod = null; // cannot use
                            }
                        }
                    }
                }
                // check whether system has 'ps' and 'kill' and might support pid determination
                if (!systemMightAllowProcessControl()) {
                    continue reaping_loop;
                }
                // check whether we can get pid for all launched processes
                // there may be a short time between Process.start() and pid being available
                // if any processes is not ready to report pid, restart loop
                for (Process p : launchedProcesses) {
                    if (waitForPidForProcess(p) == PID_UNAVAILABLE) {
                        continue reaping_loop;
                    }
                }
                // reap all perl processes which are children of process "1" (the jvm)
                if (processSurvey == null) {
                    continue reaping_loop; // "ps" failed this time, even though we know it can work on this system
                }
                for (ProcessSurveyItem psi : processSurvey) {
                    if (processShouldBeReaped(psi)) {
                        reapProcess(psi.getProcessId());
                    }
                }
            }
        }
        // shutdown was requested - reset request flag before exit
        reaperShutdownRequested = Boolean.FALSE;
        reaperIsRunning = Boolean.FALSE;
    }

    /**
    *   request that the reaper daemon thread be shut down
    */
    public static void requestReaperShutdown() {
        if (reaperIsRunning) {
            reaperShutdownRequested = true;
        }
    }

    /**
    *   whether the reaper daemon thread is running
    *   @return true if the reaper is running, false if not
    */
    public static Boolean reaperIsRunning() {
        return reaperIsRunning;
    }

    /**
    *   make the devNull stream destination available to other classes
    */
    public static File getDevNull() {
        return devNull;
    }

    private static boolean processIsZombie(ProcessSurveyItem psi) {
        if (psi.getParentProcessId() != JVM_PROCESS_ID) {
            return false; // only reap zombies which are children of the jvm
        }
        if (psi.getStateCode() != 'Z' && psi.getStateCode() != 'z') {
            return false; // only reap processes which are in zombie state
        }
        return true;
    }

    private static boolean processShouldBeReaped(ProcessSurveyItem psi) {
        if (psi.getParentProcessId() != JVM_PROCESS_ID ) {
            return false; // only reap processes which are children of the jvm
        }
        if (psi.getStateCode() != 'S' && psi.getStateCode() != 's') {
            return false; // only reap processes which are in interruptible sleep state
        }
        if (!psi.getCommand().contains("perl")) {
            return false; // only reap processes which are forked from perl command
        }
        for (Process p : launchedProcesses) {
            int launchedPid = SystemProcessManager.getProcessId(p);
            if (launchedPid == psi.getProcessId()) {
                return false; // do not reap launched / desired processes
            }
        }
        return true;
    }

    private static void reapProcess(int pid) {
        // initialize commandElements
        ArrayList<String> commandElements = new ArrayList<String>();
        commandElements.add("kill");
        commandElements.add("-9");
        commandElements.add(Integer.toString(pid));
        if (!commandSucceeded(commandElements)) {
            System.err.println("Warning : attempt to kill process " + pid + " failed");
        }
    }

    private static void determineIfPsCommandIsAvailable() {
        ArrayList<String> commandElements = new ArrayList<String>();
        commandElements.add("ps"); // lists user's processes - should always exit with status 0
        psCommandAvailableOnSystem = commandSucceeded(commandElements);
    }

    private static void determineIfKillCommandIsAvailable() {
        ArrayList<String> commandElements = new ArrayList<String>();
        commandElements.add("kill");
        commandElements.add("-l"); // lists signals - should always exit with status 0
        killCommandAvailableOnSystem = commandSucceeded(commandElements);
    }

    private static boolean commandSucceeded(ArrayList<String> commandElements) {
        // initialize processBuilder
        ProcessBuilder pb = new ProcessBuilder(commandElements);
        pb.redirectErrorStream(false);
        if (devNull != null) {
            pb.redirectError(ProcessBuilder.Redirect.to(devNull));
            pb.redirectOutput(ProcessBuilder.Redirect.to(devNull));
        }
        try {
            Process p = pb.start();
            p.waitFor(4096, TimeUnit.MILLISECONDS); // must succeed before timeout
            return p.exitValue() == 0;
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }
        return false;
    }

    private static boolean systemMightAllowProcessControl() {
        if (!psCommandAvailableOnSystem || !killCommandAvailableOnSystem) {
            return false;
        }
        if (pidDeterminationIsImpossible()) {
            return false;
        }
        return true;
    }

    private static void startReaperDaemonThreadIfNeeded() {
        if (reaperDaemonThread == null) {
            reaperDaemonThread = new Thread(new SystemProcessManager());
            reaperDaemonThread.setDaemon(true);
            reaperDaemonThread.setName("ReaperDaemon");
            reaperDaemonThread.setPriority(Thread.MIN_PRIORITY);
            reaperDaemonThread.start();
        }
    }

    private static void attemptSleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
        }
    }

    private static int waitForPidForProcess(Process p) {
        int pid = SystemProcessManager.getProcessId(p);
        if (pid == PID_UNAVAILABLE) {
            if (pidDeterminationIsImpossible()) {
                return PID_UNAVAILABLE;
            }
            attemptSleep(PID_PROBE_WAIT_PERIOD);
            // try a second time
            pid = SystemProcessManager.getProcessId(p);
        }
        return pid;
    }

    private static void determineWaitForProcessExitMethod() {
        waitForProcessExitMethod = null;
        Class<?> processClass = rememberedUnixProcess.getClass();
        Method[] classMethods = processClass.getDeclaredMethods();
        for (Method m : classMethods) {
            if (!m.getName().equals("waitForProcessExit")) {
                continue;
            }
            Class[] methodArgTypes = m.getParameterTypes();
            if (methodArgTypes.length != 1) {
                continue;
            }
            if (!methodArgTypes[0].getName().equals("int")) {
                continue;
            }
            waitForProcessExitMethod = m;
            waitForProcessExitMethod.setAccessible(true);
            break;
        }
    }

    private static void determineAvailableMethodToReadPid() {
        processPidField = null;
        Class<?> processClass = rememberedUnixProcess.getClass();
        if (UNIX_PROCESS_CLASS_NAME.equals(processClass.getName())) {
            if (processIdCanBeReadFromToStringMethod == null) {
                processIdCanBeReadFromToStringMethod = false;
                processIdCanBeReadFromToStringMethod = PID_REPORT_PATTERN.matcher(rememberedUnixProcess.toString()).find();
            }
            if (processIdCanBeReadFromUnixProcessField == null) {
                processIdCanBeReadFromUnixProcessField = false;
                try {
                    processPidField = processClass.getDeclaredField("pid");
                    if (processPidField.getType().getName().equals("int")) {
                        processPidField.setAccessible(true);
                        processIdCanBeReadFromUnixProcessField = true;
                    } else {
                        processPidField = null;
                    }
                } catch (NoSuchFieldException e) {
                    // leave processIdCanBeReadFromUnixProcessField as false if there is no pid field
                }
            }
        }
    }

    /**
    *   use some available method on the system to determine the pid of a process.
    *
    *   could be based on a toString() method which outputs pid, or a pid field in the
    *   concrete implementation class java.lang.UNIXProcess via reflection.
    *   @param process a (started) process
    *   @return the determined pid, or null if no method is available or the passed
    *           process is not yet assigned a pid on the system.
    */
    public static int getProcessId(Process process) {
        if (Boolean.TRUE.equals(processIdCanBeReadFromToStringMethod)) {
            Matcher pidReportMatcher = PID_REPORT_PATTERN.matcher(process.toString());
            if (pidReportMatcher.find()) {
                try {
                    return Integer.parseInt(pidReportMatcher.group(1));
                } catch (NumberFormatException e) {
                    throw new RuntimeException("bug detected : there is an error in logic searching for pid information for a process - " +
                            "the regular expression match returned a string of digits \"" + pidReportMatcher.group(1) +
                            "\" which could not be parsed as an integer.", e);
                } catch (IndexOutOfBoundsException e) {
                    throw new RuntimeException("bug detected : there is an error in logic searching for pid information for a process - " +
                            "the regular expression match was supposed to capture one substring of digits, but after match succeeded no " +
                            "such group existed.", e);
                }
            }
        }
        if (Boolean.TRUE.equals(processIdCanBeReadFromUnixProcessField)) {
            try {
                return processPidField.getInt(process);
            } catch (IllegalAccessException e) {
                return PID_UNAVAILABLE;
            }
        }
        return PID_UNAVAILABLE;
    }

    // if both static variables are set to Boolean.FALSE, we know that no
    // method is available. if either variable is null, it is not fully
    // known what methods are available. if either is Boolean.TRUE
    // then we know there is an available method

    private static boolean pidDeterminationIsImpossible() {
        return Boolean.FALSE.equals(processIdCanBeReadFromToStringMethod) &&
                Boolean.FALSE.equals(processIdCanBeReadFromUnixProcessField);
    }

}
