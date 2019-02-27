package org.genomenexus.vep_wrapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;

public class VepRunner {
    public static final String VEP_DEFAULT_PARAMS = null;

    public static String run(List<String> regions, Boolean convertToListJSON) throws IOException, InterruptedException {
        // get vep pameters (use -Dvep.params to change)
        String vepParameters = System.getProperty("vep.params", String.join(" ",
            "--cache",
            "--offline",
            "--everything",
            "--hgvsg",
            "--assembly",
            "GRCh37",
            "--format",
            "region",
            "--json",
            "-o",
            "STDOUT",
            "--no_stats"
        ));

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

        //Run macro on target
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(new File("/opt/vep/src/ensembl-vep"));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // send regions to stdin
        OutputStream stdin = process.getOutputStream();
        BufferedWriter stdinWriter = new BufferedWriter(new OutputStreamWriter(stdin));
        for (String region: regions) {
            stdinWriter.write(region);
            stdinWriter.write("\n");
        }
        stdinWriter.flush();
        stdinWriter.close();

        //Read output
        StringBuilder out = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = null, previous = null;
        if (convertToListJSON) {
            out.append('[');
            out.append('\n');
        }
        while ((line = br.readLine()) != null) {
            if (previous != null && convertToListJSON) {
                out.append(',');
                out.append('\n');
            }
            out.append(line);
            previous = line;
        }
        if (convertToListJSON) {
            out.append(']');
            out.append('\n');
        }

        // Check result
        if (process.waitFor() == 0) {
            //System.out.println("OK");
            //System.out.println(out.toString());
            return out.toString();
        }

        //Abnormal termination: Log command parameters and output and throw ExecutionException
        return out.toString();
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