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
import java.util.ArrayList;
import java.util.List;

//TODO : add "silent" option to inhibit System logging -- or covert to actual logging

public class VepRunner {
    private static final String INDEX_DELIMITER = "#";

    private static final String VEP_ROOT_DIRECTORY_PATH = "/opt/vep";
    private static final String VEP_WORK_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/.vep";
    private static final String VEP_TMP_DIRECTORY_PATH = VEP_WORK_DIRECTORY_PATH + "/tmp";
    private static final String VEP_SRC_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/src/ensembl-vep";
    private static final long WAIT_PERIOD_BEFORE_STREAM_CLOSING = 2000L;
    private static final String FASTA_FILE = VEP_WORK_DIRECTORY_PATH + "/homo_sapiens/98_GRCh37/Homo_sapiens.GRCh37.75.dna.primary_assembly.fa.gz";

    private static void printTimestamp() {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        System.out.println(timestamp);
    }

    private static void createTmpDirIfNecessary() throws IOException {
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
    private static void constructFileForVepProcessing(List<String> regions, Path vepInputFile) throws IOException {

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(vepInputFile))) {
            for (String region : regions) {
                out.println(region);
            }
            out.close();
        } catch (IOException e) {
            System.err.println("VepRunner : Error - could not construct input file " + vepInputFile);
            throw e;
        }
    }

    private static Path createTempFileForVepInput() throws IOException {
        return Files.createTempFile(Paths.get(VEP_TMP_DIRECTORY_PATH), "vep_input-", ".txt");
    }

    private static void forciblyCloseStreamTransfers(StreamTransferrer vepOutputTransferrer, StreamTransferrer vepErrorTransferrer) {
        vepOutputTransferrer.requestShutdown();
        vepErrorTransferrer.requestShutdown();
        //TODO : add back interrupts after debugging exception
        // try {
        //     Thread.currentThread().wait(WAIT_PERIOD_BEFORE_STREAM_CLOSING);
        // } catch (InterruptedException e) {
        //     Thread.currentThread().interrupt();
        // }
        // vepOutputTransferrer.interrupt();
        // vepErrorTransferrer.interrupt();
    }
        
    public static void run(List<String> regions, Boolean convertToListJSON, OutputStream responseOut) throws IOException, InterruptedException {
        printTimestamp();
        System.out.println("Running vep");

        createTmpDirIfNecessary();
        Path constructedInputFile = createTempFileForVepInput();

        // get vep pameters (use -Dvep.params to change)
        String vepParameters = System.getProperty("vep.params", String.join(" ",
                "--cache",
                "--offline",
                "--everything",
                "--hgvsg",
                "--assembly GRCh37",
                "--format region",
                "--fork 4",
                "--fasta " + FASTA_FILE,
                "--json",
                "-i " + constructedInputFile,
                "-o STDOUT",
                "--force_overwrite",
                "--no_stats"));

        // build command
        List<String> commandElements = new ArrayList<String>();
        commandElements.add("vep");
        for (String param : vepParameters.split(" ")) {
            commandElements.add(param);
        }

        // check reference genome environment variable and replace ref genome if necessary
        String assembly = System.getenv("VEP_ASSEMBLY");
        if (assembly != null && !"".equals(assembly)) {
            commandElements = replaceOptValue(commandElements, "--assembly", assembly);
        }

        printTimestamp();
        System.out.println("writing constructed input file");
        constructFileForVepProcessing(regions, constructedInputFile);

        printTimestamp();
        System.out.println("processing requests");
        printTimestamp();
        System.out.println("process command elements: " + commandElements);
        
        ProcessBuilder pb = new ProcessBuilder(commandElements);
        pb.directory(new File(VEP_SRC_DIRECTORY_PATH));
        //TODO : I think maybe this is where the carriage return is coming from !!
        pb.redirectErrorStream(true);
        printTimestamp();
        System.out.println("starting process using command : " + pb.command());
        Process process = pb.start();
        // send standard output from vep process to response
        FilterOutputStream filterResponseOut = null;
        if (convertToListJSON) {
            filterResponseOut = new LinesToJSONListFilterOutputStream(responseOut);
        } else {
            filterResponseOut = new FilterOutputStream(responseOut);
        }
        StreamTransferrer vepOutputTransferrer = new StreamTransferrer(process.getInputStream(), filterResponseOut, StreamTransferrer.DEFAULT_BUFFERSIZE);
        vepOutputTransferrer.start();
        // send standard error from vep process to System.err
        StreamTransferrer vepErrorTransferrer = new StreamTransferrer(process.getErrorStream(), System.err, StreamTransferrer.DEFAULT_BUFFERSIZE);
        vepErrorTransferrer.start();

        // check result
        int statusCode = process.waitFor();
        printTimestamp();
        System.out.println("vep command line process is complete");

        // close transferrers and output stream
        forciblyCloseStreamTransfers(vepOutputTransferrer, vepErrorTransferrer);
        filterResponseOut.close();

        if (statusCode == 0) {
            printTimestamp();
            System.out.println("OK");
        } else {
            //TODO: Abnormal termination: Log command parameters and output and throw ExecutionException
            System.out.println("abnormal termination");
            System.out.println("exited with status: " + statusCode);
            System.out.println("request response terminated before completion");
        }
        Files.deleteIfExists(constructedInputFile);
    }

    /**
     * Function to replace a specific value in the VEP parameters list.
     */
    private static List<String> replaceOptValue(List<String> commandElements, String optionName, String newValue) {
        List<String> result = new ArrayList<String>();
        boolean substituteNext = false;
        for (String commandElement : commandElements) {
            // Find argument to replace
            if (commandElement.equals(optionName)) {
                result.add(commandElement);

                // Replace value
                result.add(newValue);
                substituteNext = true;
            } else {
                // Skip value if it was replaced in the previous iteration
                if (substituteNext) {
                    substituteNext = false;
                } else {
                    result.add(commandElement);
                }
            }
        }
        return result;
    }
}
