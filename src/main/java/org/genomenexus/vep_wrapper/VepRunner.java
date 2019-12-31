package org.genomenexus.vep_wrapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;

public class VepRunner {
    private static final String INDEX_DELIMITER = "#";

    private static final String VEP_ROOT_DIRECTORY_PATH = "/opt/vep";
    private static final String VEP_WORK_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/.vep";
    private static final String VEP_TMP_DIRECTORY_PATH = VEP_WORK_DIRECTORY_PATH + "/tmp";
    private static final String VEP_SRC_DIRECTORY_PATH = VEP_ROOT_DIRECTORY_PATH + "/src/ensembl-vep";
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

    public static void readResults(Boolean convertToListJSON, Path vepOutputFile, OutputStream responseOut) {
        PrintWriter responseWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(responseOut)));
        if (convertToListJSON) {
            responseWriter.write("[\n");
        }
        //TODO: there is a potential performance gain here if we avoid reading the file line by line and instead use IOUtils.copy(myStream, response.getOutputStream());
        //      in order to do that, we would need to use an external program to add a trailing comma on the end of every line in the vep output file
        try (BufferedReader br = Files.newBufferedReader(vepOutputFile)) {
            String line;
            Boolean firstLineWasRead = false;
            while ((line = br.readLine()) != null) {
                if (firstLineWasRead && convertToListJSON) {
                    responseWriter.write(",\n");
                }
                responseWriter.write(line);
                firstLineWasRead = true;
            }
        } catch (IOException e) {
            System.err.println("Error - could not read results file " + vepOutputFile);
            System.exit(5);
        }
        if (convertToListJSON) {
            responseWriter.write("]\n");
        }
        responseWriter.flush();
    }

    private static Path createTempFileForVepInput() throws IOException {
        return Files.createTempFile(Paths.get(VEP_TMP_DIRECTORY_PATH), "vep_input-", ".txt");
    }

    private static Path createTempFileForVepOutput(Path tempFileForVepInput) throws IOException {
        return Files.createFile(Paths.get(tempFileForVepInput + "_output"));
    }

    public static void run(List<String> regions, Boolean convertToListJSON, OutputStream responseOut) throws IOException, InterruptedException {
        printTimestamp();
        System.out.println("Running vep");

        createTmpDirIfNecessary();
        Path constructedInputFile = createTempFileForVepInput();
        Path vepOutputFile = createTempFileForVepOutput(constructedInputFile);

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
                "-o " + vepOutputFile,
                "--force_overwrite",
                "--no_stats"));

        //Build command
        List<String> commands = new ArrayList<String>();
        commands.add("vep");
        for (String param : vepParameters.split(" ")) {
            commands.add(param);
        }

        // Check reference genome environment variable and replace ref genome if necessary
        String assembly = System.getenv("VEP_ASSEMBLY");
        if (assembly != null && !"".equals(assembly)) {
            commands = replaceOptValue(commands, "--assembly", assembly);
        }

        printTimestamp();
        System.out.println("writing constructed input file");
        constructFileForVepProcessing(regions, constructedInputFile);

        printTimestamp();
        System.out.println("processing requests");
        printTimestamp();
        System.out.println("running command: " + commands);
        //Run macro on target
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(new File(VEP_SRC_DIRECTORY_PATH));
        pb.redirectErrorStream(true);
        printTimestamp();
        System.out.println("starting..");
        Process process = pb.start();

        // Check result
        int statusCode = process.waitFor();
        printTimestamp();
        System.out.println("done processing requests");

        //Read output from process, this is not our result but might contain errors or something we want to read
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line);
            }
        }
        printTimestamp();
        System.out.println("stdout and stderr:");
        System.out.println(out.toString());

        if (statusCode == 0) {
            printTimestamp();
            System.out.println("OK");
            readResults(convertToListJSON, vepOutputFile, responseOut);
        } else {
            //TODO: Abnormal termination: Log command parameters and output and throw ExecutionException
            System.out.println("abnormal termination");
            System.out.println("exited with status: " + statusCode);
            System.out.println("return empty string to user");
        }
        Files.deleteIfExists(constructedInputFile);
    }

    /**
     * Function to replace a specific value in the VEP parameters list.
     */
    private static List<String> replaceOptValue(List<String> commands, String optionName, String newValue) {
        List<String> result = new ArrayList<String>();
        boolean substituteNext = false;
        for (String command : commands) {
            // Find argument to replace
            if (command.equals(optionName)) {
                result.add(command);

                // Replace value
                result.add(newValue);
                substituteNext = true;
            } else {
                // Skip value if it was replaced in the previous iteration
                if (substituteNext) {
                    substituteNext = false;
                } else {
                    result.add(command);
                }
            }
        }
        return result;
    }
}
