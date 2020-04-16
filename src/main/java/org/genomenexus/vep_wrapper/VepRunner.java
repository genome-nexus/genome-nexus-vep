package org.genomenexus.vep_wrapper;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VepRunner {
    private static final String INDEX_DELIMITER = "#";

    private static final String VEP_ROOT_DIRECTORY_PATH = "/opt/vep";
    private static final String VEP_WORK_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/.vep";
    private static final String VEP_TMP_DIRECTORY_PATH = VEP_WORK_DIRECTORY_PATH + "/tmp";
    private static final String VEP_SRC_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/src/ensembl-vep";
    private static final Path VEP_WORK_DIRECTORY = Paths.get(VEP_WORK_DIRECTORY_PATH);
    private static final long WAIT_PERIOD_BEFORE_STREAM_CLOSING = 2000L;
    private static final Integer MAIN_PROCESS_WAIT_PERIOD = 1;
    private static final int MAX_VEP_OUTPUT_RECORD_SIZE = 384 * 1024;

    @Value("${verbose:false}")
    private Boolean verbose;

    @Value("${vep.fork_count:4}")
    private String vepForkCount;

    @Value("${vep.assembly:GRCh37}")
    private String vepAssembly;

    // Path is relative to the VEP_WORK_DIRECTORY_PATH
    @Value("${vep.fastaFileRelativePath:homo_sapiens/98_GRCh37/Homo_sapiens.GRCh37.75.dna.primary_assembly.fa.gz}")
    private String vepFastaFileRelativePath;

    private Path vepFastaFilePath;
    @Autowired
    private void setVepFastaFilePath() {
        vepFastaFilePath = VEP_WORK_DIRECTORY.resolve(vepFastaFileRelativePath);
    }

    private void printWithTimestamp(String msg) {
        if (verbose) {
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            System.out.println(timestamp + ": " + msg);
        }
    }

    private void createTmpDirIfNecessary() throws IOException {
        Path tmpDirPath = Paths.get(VEP_TMP_DIRECTORY_PATH);
        if (!Files.exists(tmpDirPath)) {
            Files.createDirectory(Paths.get(VEP_TMP_DIRECTORY_PATH));
        }
    }

    /**
     * Create a file containing the regions received in the input query.
     * Write the user supplied regions from the "regions" argument to an output file.
     * CAUTION : this function does not sort the regions into chromosomal order. The
     * VEP command line tool is very slow when the input is not sorted. It is expected
     * that users of the VepRunner will always send requests that have been pre-sorted.
     *
     * @param regions - the regions as passed by the user
     * @param vepInputFile - the file to be written
     * @return sum of two operands

    **/
    private void constructFileForVepProcessing(List<String> regions, Path vepInputFile) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(vepInputFile))) {
            for (String region : regions) {
                out.println(region);
            }
            out.close();
        } catch (IOException e) {
            printWithTimestamp("VepRunner : Error - could not construct input file " + vepInputFile);
            throw e;
        }
    }

    private Path createTempFileForVepInput() throws IOException {
        return Files.createTempFile(Paths.get(VEP_TMP_DIRECTORY_PATH), "vep_input-", ".txt");
    }

    private void forciblyCloseStreamTransfers(StreamTransferrer vepOutputTransferrer, StreamTransferrer vepErrorTransferrer) {
        printWithTimestamp("requesting shutdown");
        vepOutputTransferrer.requestShutdown();
        vepErrorTransferrer.requestShutdown();
        if (vepOutputTransferrer.isAlive() || vepErrorTransferrer.isAlive()) {
            printWithTimestamp("waiting 2 seconds..");
            try {
                Thread.currentThread().sleep(WAIT_PERIOD_BEFORE_STREAM_CLOSING);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            printWithTimestamp("interrupting transferrer threads");
            vepOutputTransferrer.interrupt();
            vepErrorTransferrer.interrupt();
            // TODO: when the destroy of the vep process is made to destory all forked processes as well as the parent process, the code below can be tested/used:
            /*
            try {
                printWithTimestamp("waiting until vep stdout thread dies..");
                vepOutputTransferrer.join();
                printWithTimestamp("waiting until vep stderr thread dies..");
                vepErrorTransferrer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            */
        }
    }

    public Instant computeTimeToKillProcess(Integer responseTimeout) {
        return Instant.now().plusSeconds(responseTimeout);
    }

    public boolean timeIsExpired(Instant timeToKillProcess) {
        return Instant.now().isAfter(timeToKillProcess);
    }

    public void run(List<String> regions, Boolean convertToListJSON, Integer responseTimeout, OutputStream responseOut) throws IOException, InterruptedException {

        printWithTimestamp("Running vep");

        createTmpDirIfNecessary();
        Path constructedInputFile = createTempFileForVepInput();

        // get vep pameters (use -Dvep.params to change)
        String vepParameters = System.getProperty("vep.params", String.join(" ",
                "--cache",
                "--offline",
                "--everything",
                "--hgvsg",
                "--xref_refseq",
                "--assembly " + vepAssembly,
                "--format region",
                "--fork " + vepForkCount,
                "--fasta " + vepFastaFilePath,
                "--json",
                "-i " + constructedInputFile,
                "-o STDOUT",
                "--no_stats"));

        // build command
        List<String> commandElements = new ArrayList<String>();
        commandElements.add("vep");
        for (String param : vepParameters.split(" ")) {
            commandElements.add(param);
        }

        printWithTimestamp("writing constructed input file");
        constructFileForVepProcessing(regions, constructedInputFile);

        printWithTimestamp("processing requests");
        printWithTimestamp("process command elements: " + commandElements);

        System.err.println("argument for repsoneTimeout: " + Integer.toString(responseTimeout));

        ProcessBuilder pb = new ProcessBuilder(commandElements);
        pb.directory(new File(VEP_SRC_DIRECTORY_PATH));
        pb.redirectErrorStream(false);
        printWithTimestamp("starting process using command : " + pb.command());
        Process process = pb.start();
        // send standard output from vep process to response
        CompleteLineBufferedOutputStream completeLineResponseOut = new CompleteLineBufferedOutputStream(responseOut, MAX_VEP_OUTPUT_RECORD_SIZE);
        FilterOutputStream filterResponseOut = null;
        LinesToJSONListFilterOutputStream completableFilterOutputStream = null; // when formatting JSON list output, this type allows the completion of the list without closing the stream
        if (convertToListJSON) {
            completableFilterOutputStream = new LinesToJSONListFilterOutputStream(responseOut);
            filterResponseOut = new CompleteLineBufferedOutputStream(completableFilterOutputStream, MAX_VEP_OUTPUT_RECORD_SIZE);
        } else {
            filterResponseOut = new FilterOutputStream(completeLineResponseOut);
        }
        StreamTransferrer vepOutputTransferrer = new StreamTransferrer(process.getInputStream(), filterResponseOut, StreamTransferrer.DEFAULT_BUFFERSIZE, "vep stdout");
        vepOutputTransferrer.start();
        // send standard error from vep process to System.err
        StreamTransferrer vepErrorTransferrer = new StreamTransferrer(process.getErrorStream(), System.err, StreamTransferrer.DEFAULT_BUFFERSIZE, "vep stderr");
        vepErrorTransferrer.start();

        // check result
        boolean responseTimeoutSupplied = responseTimeout != 0;
        Instant timeToKillProcess = computeTimeToKillProcess(responseTimeout);
        boolean processCompletedNaturally = false;
        while ((!responseTimeoutSupplied || !timeIsExpired(timeToKillProcess)) && !processCompletedNaturally) {
            // wait briefly while process is running
            processCompletedNaturally = process.waitFor(MAIN_PROCESS_WAIT_PERIOD, TimeUnit.SECONDS);
        }

        if (!processCompletedNaturally) {
            System.err.println("destroying process which did not complete naturally");
            process.destroy();
            // close transferrers and output stream
            forciblyCloseStreamTransfers(vepOutputTransferrer, vepErrorTransferrer);
            if (vepOutputTransferrer.isAlive()) {
                printWithTimestamp("vep stdout thread is still alive!");
            } else {
                printWithTimestamp("vep stdout thread is dead");
            }
            if (vepErrorTransferrer.isAlive()) {
                printWithTimestamp("vep stderr thread is still alive!");
            } else {
                printWithTimestamp("vep stderr thread is dead");
            }
            completeLineResponseOut.purge(); // drop any partial record in buffer
        } else {
            printWithTimestamp("vep command line process is complete");
            // wait for all stream output to be transferred to destination
            vepOutputTransferrer.join();
            vepErrorTransferrer.join();
        }

        if (convertToListJSON) {
            completableFilterOutputStream.complete(); // close an open JSON list
        }

        if (process.exitValue() == 0) {
            printWithTimestamp("OK");
        } else {
            //TODO: Abnormal termination: Log command parameters and output and throw ExecutionException
            printWithTimestamp("abnormal termination");
            printWithTimestamp("exited with status: " + process.exitValue());
            printWithTimestamp("request response terminated before completion");
        }
        Files.deleteIfExists(constructedInputFile);
    }

}
