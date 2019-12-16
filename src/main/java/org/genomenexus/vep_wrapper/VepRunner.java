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
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;

public class VepRunner {
    private static int[] processingOrder = null; // a reordering of the request to put them into chromosomal order for processing
    private static int[] responseOrder = null; // a reordering of the processing output to restore the original request order in our response

    private static final String INDEX_DELIMITER = "#";

    private static final boolean sort = false;
    private static final String CONSTRUCTED_INPUT_FILENAME = "/opt/vep/.vep/input/constructed_input_file.txt";
    private static final String RESULTS_OUTPUT_FILENAME = "/opt/vep/.vep/output/output_from_constructed_input.txt";

    private static void printTimestamp() {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        System.out.println(timestamp);
    }

    private static void computeOrders(List<String> requestList) {
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

    private static void writeRegionsToConstructedInput(List<String> regions) throws IOException {
        try {
            PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(CONSTRUCTED_INPUT_FILENAME)));
            for (String region : regions) {
                out.println(region);
            }
            out.close();
        } catch (IOException e) {
            System.err.println("VepRunner : Error - could not construct input file " +  CONSTRUCTED_INPUT_FILENAME);
            throw e;
        }
    }

    public static String readResults(Boolean convertToListJSON) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(RESULTS_OUTPUT_FILENAME))) {
            if (convertToListJSON) {
                out.append('[');
                out.append('\n');
            }
            String line;
            Boolean firstLineWasRead = false;
            while ((line = br.readLine()) != null) {
                if (!firstLineWasRead && convertToListJSON) {
                    out.append(',');
                    out.append('\n');
                }
                out.append(line);
                firstLineWasRead = true;
            }
            if (convertToListJSON) {
                if (!firstLineWasRead) {
                    // cover the case where the output file is completely empty -- return empty result json
                    out.append('[');
                    out.append('\n');
                }
                out.append(']');
                out.append('\n');
            }
        } catch (IOException e) {
            // TODO logging?
            System.err.println("Error - could not read results file " + RESULTS_OUTPUT_FILENAME);
            System.exit(5);
        }
        return out.toString();
    }

    public static String run(List<String> regions, Boolean convertToListJSON) throws IOException, InterruptedException {
        printTimestamp();
        System.out.println("Running vep");
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
                "-i " + CONSTRUCTED_INPUT_FILENAME,
                "-o " + RESULTS_OUTPUT_FILENAME,
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


if (sort) {
        // compute forward and backword reordering
        printTimestamp();
        System.out.println("computing order..");
        computeOrders(regions);
        printTimestamp();
        System.out.println("done computing order");
}

//if (sort) {
//        for (int index : processingOrder) {
//            String region = regions.get(index);
//        }


        printTimestamp();
        System.out.println("writing constructed input file");
        writeRegionsToConstructedInput(regions);

        printTimestamp();
        System.out.println("processing requests");
        printTimestamp();
        System.out.println("running command: " + commands);
        //Run macro on target
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(new File("/opt/vep/src/ensembl-vep"));
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
            return readResults(convertToListJSON);
        } else {
            //TODO: Abnormal termination: Log command parameters and output and throw ExecutionException
            System.out.println("abnormal termination");
            System.out.println("exited with status: " + statusCode);
            System.out.println("return empty string to user");
            return "";
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
