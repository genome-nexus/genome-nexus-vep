package org.genomenexus.vep_wrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VEPService {

    @Autowired
    private VEPConfiguration vepConfiguration;

    public String annotateVariants(List<List<String>> variantChunks, String format) throws Exception {
        List<Callable<VEPResult>> wrappers = new ArrayList<>();
        for (List<String> chunk : variantChunks) {
            List<String> flags = new ArrayList<>();
            Collections.addAll(flags,
                "--database",
                "--host=" + vepConfiguration.getHost(),
                "--port=" + vepConfiguration.getPort(),
                "--user=" + vepConfiguration.getUsername(),
                "--password=" + vepConfiguration.getPassword(),
                "--fork=" + vepConfiguration.getForks(),
                "--format=" + format,
                "--input_data=" + chunk.stream().collect(Collectors.joining("\n")),
                "--output_file=STDOUT",
                "--warning_file=STDERR",
                "--everything",
                "--hgvsg",
                "--no_stats",
                "--xref_refseq",
                "--json"
            );
            if (StringUtils.hasText(vepConfiguration.getPolyphenSiftFilename())) {
                flags.add("--plugin=PolyPhen_SIFT,db=/plugin-data/" + vepConfiguration.getPolyphenSiftFilename());
            }
            if (StringUtils.hasText(vepConfiguration.getAlphaMissenseFilename())) {
                flags.add("--plugin=AlphaMissense,file=/plugin-data/" + vepConfiguration.getAlphaMissenseFilename());
            }
            wrappers.add(runVEP(flags));
        }

        String output = "";
        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<Future<VEPResult>> resultFutures = threadPool.invokeAll(wrappers);

        Exception exception = null;
        boolean allFailed = true;
        for (Future<VEPResult> resultFuture : resultFutures) {
            VEPResult result = resultFuture.get();
            if (result.getExitCode() == 0) {
                output += result.getOutput();
                allFailed = false;
            } else if (exception == null) { // Ensembl VEP API only returns first error, so copying behavior
                exception = new Exception(result.getOutput());
            }
        }

        if (allFailed) {
            throw exception;
        }

        output = output.replace("sift_pred", "sift_prediction");
        output = output.replace("polyphen_humvar_pred", "polyphen_prediction");
        output = output.replace("polyphen_humvar_score", "polyphen_score");
        return "[" + output.replace("\n{", ",{") + "]";
    }

    public List<List<String>> getVariantChunks(List<String> variants, int chunkSize) {
        List<List<String>> variantChunks = new ArrayList<>();
        int numVariants = variants.size();
        int maxThreads = vepConfiguration.getHgvsMaxThreads();

        if ((float)numVariants / (float)chunkSize > maxThreads) {
            chunkSize = numVariants / maxThreads;
            if (numVariants % maxThreads != 0) {
                chunkSize++;
            }
        }

        for (int i = 0; i < numVariants; i += chunkSize) {
            variantChunks.add(variants.subList(i, Math.min(chunkSize + i, numVariants)));
        }
        return variantChunks;
    }

    public List<List<String>> getVariantChunksByChromosome(List<String> variants) {
        List<List<String>> variantChunks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            variantChunks.add(new ArrayList<>());
        }

        for (String variant : variants) {
            String chromosome = variant.split(":")[0];
            if (chromosome.toLowerCase().equals("x")) {
                variantChunks.get(23).add(variant);
            } else if (chromosome.toLowerCase().equals( "y")) {
                variantChunks.get(24).add(variant);
            } else {
                variantChunks.get(Integer.parseInt(chromosome)).add(variant);
            }
        }
        return variantChunks.stream().filter(chunk -> chunk.size() > 0).collect(Collectors.toList());
    }

    public int getVEPVersion() throws Exception {
        VEPResult result = runVEP(new ArrayList<>()).call();
        if (result.getExitCode() == 1) {
            throw new Exception(result.getOutput());
        }

        String versionRegex = "ensembl-vep\\s*:\\s*(\\d+)";
        Pattern pattern = Pattern.compile(versionRegex);
        Matcher matcher = pattern.matcher(result.getOutput());
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        } else {
            throw new Exception("Version not found in VEP output");
        }
    }

    public Callable<VEPResult> runVEP(List<String> flags) {
        return new Callable<VEPResult>() {
            @Override
            public VEPResult call() throws Exception {
                String path = Paths.get("").toAbsolutePath().toString() + "/scripts/vep";

                String output = "";
                int exitCode = 0;
                try {
                    flags.add(0, path);
                    Process process = new ProcessBuilder().command(flags).start();
                         
                    StringBuilder outputBuilder = new StringBuilder();
                    StringBuilder errorBuilder = new StringBuilder();
                    BufferedReader stdin = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                    String line = null;
                    while ( (line = stdin.readLine()) != null) {
                        outputBuilder.append(line);
                        outputBuilder.append(System.getProperty("line.separator"));
                    }
                    while ( (line = stderr.readLine()) != null) {
                        errorBuilder.append(line);
                        errorBuilder.append(System.getProperty("line.separator"));
                    }    
                    stdin.close();
                    stderr.close();

                    output = outputBuilder.toString();
                    String error = errorBuilder.toString();
                    if (!StringUtils.hasText(output) && StringUtils.hasText(error)) {
                        output = parseVepError(error);
                        exitCode = 500;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } 

                return new VEPResult(output, exitCode);
            }
        };
    }

    private String parseVepError(String error) {
        String warning = null;
        String message = null;

        String warningRegex = "WARNING:\\s(.*)\\n";
        Pattern pattern = Pattern.compile(warningRegex);
        Matcher matcher = pattern.matcher(error);
        if (matcher.find()) {
            warning = matcher.group(1);
        }

        String messageRegex =  "MSG:\\s(.*)\\n";
        pattern = Pattern.compile(messageRegex);
        matcher = pattern.matcher(error);
        if (matcher.find()) {
            message = matcher.group(1);
        }

        String output = "";
        if (warning != null) {
            output += warning;
        } 
        if (message != null) {
            output += message;
        } else {
            output += "Error annotating variant";
        }
        return output;
    }
}