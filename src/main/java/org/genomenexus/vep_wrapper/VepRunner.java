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

    private static void computeOrders(List<String> requestList, int[] processingOrder, int[] responseOrder) {
        ArrayList<String> workingRequestOrder = new ArrayList<String>();
        processingOrder = new int[requestList.size()];
        responseOrder = new int[requestList.size()];
        System.out.println("computing order of input list (list size: " + requestList.size() + ")");
        int index = 0;
        for (String request : requestList) {
            workingRequestOrder.add(request + INDEX_DELIMITER + Integer.toString(index));
            index = index + 1;
        }
        Collections.sort(workingRequestOrder);
        int sortedIndex= 0;
        for (String request : workingRequestOrder) {
            String[] parts = request.split(INDEX_DELIMITER);
            if (parts.length < 2) {
                System.out.println("something bad happened during split of working order");
                System.exit(3);
            }
            try {
                int originalIndex = Integer.parseInt(parts[1]);
                processingOrder[originalIndex] = sortedIndex;
                responseOrder[sortedIndex] = originalIndex;
            } catch (NumberFormatException e) {
                System.out.println("something bad happened during parse of offset of working order");
                System.exit(3);
            }
            sortedIndex = sortedIndex + 1;
        }
    }

    /**
     * create a "chromosomal order" file containing the regions received in the input query.
     * Write the user supplied regions from the "regions" argument in the order supplied in the processingOrder argument,
     * to an output file.
     *
     * @param regions - the regions as passed by the user
     * @param processingOrder - maps the user query index position to the "chromosomal sort" index position for each region.
     *                          The index of processingOrder is the line you are writing for the vep input file.
     *                          The value at the index is the index of the record you want on that line from the passed in request.
     * @param sortedVepInputFile - the file to be written
     * @return sum of two operands

    **/
    private static void constructSortedFileForVepProcessing(List<String> regions, int[] processingOrder, Path sortedVepInputFile) throws IOException {

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(sortedVepInputFile))) {
            for (int index = 0; index < regions.size(); index++) {
                int nextIndexInSortOrder = processingOrder[index];
                out.println(regions.get(nextIndexInSortOrder));
            }
            out.close();
        } catch (IOException e) {
            System.err.println("VepRunner : Error - could not construct input file " + sortedVepInputFile);
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
    }

    private static Path createTempFileForVepInput() throws IOException {
        return Files.createTempFile(Paths.get(VEP_TMP_DIRECTORY_PATH), "vep_input-", "-sorted.txt");
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
                "--fasta /opt/vep/.vep/homo_sapiens/98_GRCh37/Homo_sapiens.GRCh37.75.dna.primary_assembly.fa.gz",
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

        // compute forward and backword reordering
        printTimestamp();
        System.out.println("computing order..");
        int[] processingOrder = null; // a reordering of the request to put them into chromosomal order for processing
        int[] responseOrder = null; // a reordering of the processing output to restore the original request order in our response
        computeOrders(regions, processingOrder, responseOrder);
        printTimestamp();
        System.out.println("done computing order");

        printTimestamp();
        System.out.println("writing constructed input file");
        constructSortedFileForVepProcessing(regions, processingOrder, constructedInputFile);

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
            //TODO: constructedInputFile.delete();
            readResults(convertToListJSON, vepOutputFile, responseOut);
        } else {
            //TODO: Abnormal termination: Log command parameters and output and throw ExecutionException
            System.out.println("abnormal termination");
            System.out.println("exited with status: " + statusCode);
            System.out.println("return empty string to user");
            Files.deleteIfExists(constructedInputFile);
        }
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
