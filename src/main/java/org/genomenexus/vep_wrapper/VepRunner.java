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

    @Value("${database.host}")
    private String databaseHost;

    @Value("${database.port}")
    private String databasePort;

    @Value("${database.user}")
    private String databaseUser;

    @Value("${database.password}")
    private String databasePassword;

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
     * Create a file containing the variants received in the input query.
     * Write the user supplied variants from the "variants" argument to an output file.
     * CAUTION : this function does not sort the variants into chromosomal order. The
     * VEP command line tool is very slow when the input is not sorted. It is expected
     * that users of the VepRunner will always send requests that have been pre-sorted.
     *
     * @param variants - the variants as passed by the user
     * @param vepInputFile - the file to be written
     * @return sum of two operands

    **/
    private void constructFileForVepProcessing(List<String> variants, Path vepInputFile) throws IOException {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(vepInputFile))) {
            for (String variant : variants) {
                out.println(variant);
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

    private void closeStreamTransfers(StreamTransferrer vepOutputTransferrer, StreamTransferrer vepErrorTransferrer) {
        printWithTimestamp("requesting stream transfer shutdown");
        vepOutputTransferrer.requestShutdown();
        vepErrorTransferrer.requestShutdown();
        try {
            vepOutputTransferrer.join();
            vepErrorTransferrer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Instant computeTimeToKillProcess(Integer responseTimeout) {
        return Instant.now().plusSeconds(responseTimeout);
    }

    public boolean timeIsExpired(Instant timeToKillProcess) {
        return Instant.now().isAfter(timeToKillProcess);
    }

    public void run(List<String> variants, Boolean convertToListJSON, Integer responseTimeout, OutputStream responseOut, boolean useDatabase)
            throws IOException, InterruptedException, VepLaunchFailureException {

        printWithTimestamp("Running vep");

        createTmpDirIfNecessary();
        Path constructedInputFile = createTempFileForVepInput();

        // get vep parameters (use -Dvep.params to change)
        String vepParameters;
        if (useDatabase) {
            vepParameters = System.getProperty("vep.params", String.join(" ",
            "--database",
                "--host " + databaseHost,
                "--user " + databaseUser,
                "--password " + databasePassword,
                "--port " + databasePort,
                "--everything",
                "--hgvsg",
                "--xref_refseq",
                "--format hgvs",
                "--fork 11",
                "--fasta " + vepFastaFilePath,
                "--json",
                "-i " + constructedInputFile,
                "-o STDOUT",
                "--no_stats"));
        } else {
            vepParameters = System.getProperty("vep.params", String.join(" ",
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
        }

        // build command
        List<String> commandElements = new ArrayList<String>();
        commandElements.add("vep");
        for (String param : vepParameters.split(" ")) { // warning -- spaces in paths will break this code
            commandElements.add(param);
        }

        printWithTimestamp("writing constructed input file");
        constructFileForVepProcessing(variants, constructedInputFile);

        printWithTimestamp("processing requests");
        printWithTimestamp("process command elements: " + commandElements);

        System.err.println("argument for responseTimeout: " + Integer.toString(responseTimeout));

        ProcessBuilder pb = new ProcessBuilder(commandElements);
        pb.directory(new File(VEP_SRC_DIRECTORY_PATH));
        pb.redirectErrorStream(false);
        printWithTimestamp("starting process using command : " + pb.command());
        // SystemProcessManager will handle proper tracking and cleanup of vep jobs
        Process process = SystemProcessManager.launchVepProcess(pb);
        if (process == null) {
            printWithTimestamp("could not create process: " + String.join(" ", commandElements));
            Files.deleteIfExists(constructedInputFile);
            throw new VepLaunchFailureException("unable to process request");
        }
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
            SystemProcessManager.destroyVepProcess(process);
            closeStreamTransfers(vepOutputTransferrer, vepErrorTransferrer);
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
