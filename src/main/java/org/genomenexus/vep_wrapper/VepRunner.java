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

public class VepRunner {
    public static String run(List<String> regions, Boolean convertToListJSON) throws IOException, InterruptedException {
        //Build command 
        List<String> commands = new ArrayList<String>();
        commands.add("vep");
        commands.add("--cache");
        commands.add("--offline");
        commands.add("--fasta");
        commands.add("/opt/vep/.vep/homo_sapiens/92_GRCh37/Homo_sapiens.GRCh37.75.dna.primary_assembly.fa.gz");
        commands.add("--symbol");
        commands.add("--hgvs");
        commands.add("--hgvsg");
        //commands.add("--port");
        //commands.add("3337");
        commands.add("--assembly");
        commands.add("GRCh37");
        commands.add("--format");
        commands.add("region");
        commands.add("--json");
        commands.add("-o");
        commands.add("STDOUT");
        commands.add("--no_stats");

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

        //Check result
        if (process.waitFor() == 0) {
            //System.out.println("OK");
            //System.out.println(out.toString());
            return out.toString();
        }

        //Abnormal termination: Log command parameters and output and throw ExecutionException
        return out.toString();
    }
}