package org.genomenexus.vep_wrapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class VepRunner {
    public static String run(String hgvsVariant) throws IOException, InterruptedException {
        //Build command 
        List<String> commands = new ArrayList<String>();
        commands.add("./vep");
        commands.add("--input_data");
        commands.add("17:g.41242962_41242963insGA");
        commands.add("--port");
        commands.add("3337");
        commands.add("--assembly");
        commands.add("GRCh37");
        commands.add("--format");
        commands.add("hgvs");
        commands.add("--database");
        commands.add("--json");
        commands.add("--everything");
        commands.add("-o");
        commands.add("STDOUT");
        commands.add("--force_overwrite");
        //Add arguments
        // commands.add("/home/narek/pk.txt");
        System.out.println(commands);

        //Run macro on target
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(new File("/home/vep/src/ensembl-vep"));
        pb.redirectErrorStream(true);
        Process process = pb.start();

        //Read output
        StringBuilder out = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = null, previous = null;
        while ((line = br.readLine()) != null)
            if (!line.equals(previous)) {
                previous = line;
                out.append(line).append('\n');
                System.out.println(line);
            }

        //Check result
        if (process.waitFor() == 0) {
            System.out.println("Success!");
            return out.toString();
        }

        //Abnormal termination: Log command parameters and output and throw ExecutionException
        return out.toString();
    }
}