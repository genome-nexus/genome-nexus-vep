package org.genomenexus.vep_wrapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.*;

/** Returns a list of elements representing all processes on system
*/
public class ProcessSurveyor {

    private static ArrayList<String> commandElements;
    private static ProcessBuilder psProcessBuilder;

    static {
        // initialize commandElements
        commandElements = new ArrayList<String>();
        commandElements.add("ps");
        commandElements.add("axo");
        commandElements.add("pid,ppid,state,comm");
        // initialize processBuilder
        psProcessBuilder = new ProcessBuilder(commandElements);
        psProcessBuilder.redirectErrorStream(false);
        if (SystemProcessManager.getDevNull() != null) {
            psProcessBuilder.redirectError(ProcessBuilder.Redirect.to(SystemProcessManager.getDevNull()));
        }
    }

    private static ProcessSurveyItem parsePsOutputLine(String line) {
        // each output line from the ps command will have 4 fields, such as these example lines:
        //  PID  PPID S COMMAND
        //    1     1 S java
        //   46     1 R perl
        //   48     1 R ps
        // the leading spaces are not guaranteed to be present
        if (line == null || line.length() < 8) {
            return null; // not enough content in line
        }
        String[] fields = line.split("[ ]+");
        if (fields.length < 4) {
            return null; // not enough fields to parse
        }
        int firstFieldIndex = 0;
        if (fields[0].length() == 0) {
            // skip empty field due to leading spaces
            firstFieldIndex = firstFieldIndex + 1;
        }
        int processId = 0;
        try {
            processId = Integer.parseInt(fields[firstFieldIndex]);
        } catch (NumberFormatException e) {
            return null; // first column is not a number .. such as in the header line
        }
        int parentProcessId = 0;
        try {
            parentProcessId = Integer.parseInt(fields[firstFieldIndex + 1]);
        } catch (NumberFormatException e) {
            return null; // first column is not a number .. such as in the header line
        }
        if (fields[firstFieldIndex + 2].length() != 1) {
            return null;
        }
        char stateCode = fields[firstFieldIndex + 2].charAt(0);
        if (fields[firstFieldIndex + 3].length() == 0) {
            return null;
        }
        return new ProcessSurveyItem(processId, parentProcessId, stateCode, fields[firstFieldIndex + 3]);
    }

    /** use command "ps axo pid,ppid,state,comm" in a forked process on the system and parse output
    *   @return a list of ProcessSurveyItem representing each process in the output
    */ 
    public static List<ProcessSurveyItem> getProcessSurvey() {
        ArrayList<ProcessSurveyItem> processSurvey = new ArrayList<ProcessSurveyItem>();
        try {
            Process psProcess = psProcessBuilder.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(psProcess.getInputStream()));
            String line;
            while ((line = in.readLine()) != null) {
                ProcessSurveyItem item = parsePsOutputLine(line);
                if (item != null) {
                    processSurvey.add(item);
                }
            }
            psProcess.waitFor();
            if (psProcess.exitValue() != 0) {
                // the ps command is not in the available path, or does not provide the formats used
                return null; // total failure
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return processSurvey;
    }
}
