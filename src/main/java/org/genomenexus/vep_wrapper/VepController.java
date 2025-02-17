package org.genomenexus.vep_wrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VEPController {

    @Value("${app.version}")
    private String serverVersion;

    @Autowired
    private VEPConfiguration vepConfiguration;

    @GetMapping("/vep/human/hgvs/{variant}")
    public ResponseEntity<String> annotateHGVS(@PathVariable String variant) {
        List<List<String>> variantChunks = new ArrayList<>();
        variantChunks.add(Arrays.asList(variant));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(annotateVariants(variantChunks, "hgvs"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/vep/human/hgvs")
    public ResponseEntity<String> annotateHGVS(@RequestBody HashMap<String, List<String>> variants) {
        List<String> variantList = variants.get("hgvs_notations");
        if (variantList == null) {
            return ResponseEntity.badRequest().body(("Missing key: 'hgvs_notations'"));
        }

        List<List<String>> variantChunks = getVariantChunks(variantList, 10);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(annotateVariants(variantChunks, "hgvs"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/vep/human/region/{*variant}")
    public ResponseEntity<String> annotateRegion(@PathVariable String variant) {
        List<List<String>> variantChunks = new ArrayList<>();       
        variantChunks.add(Arrays.asList(variant.substring(1)));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(annotateVariants(variantChunks, "region"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/vep/human/region")
    public ResponseEntity<String> annotateRegion(@RequestBody List<String> variants) {
        List<List<String>> variantChunks = getVariantChunksByChromosome(variants);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(annotateVariants(variantChunks, "region"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @GetMapping("/info/software")
    public ResponseEntity<Object> getVEPSoftwareVersion() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("server", serverVersion);
            response.put("release", getVEPVersion());
            return ResponseEntity.ok().body(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    private String annotateVariants(List<List<String>> variantChunks, String format) throws Exception {
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
        for (Future<VEPResult> resultFuture : resultFutures) {
            VEPResult result = resultFuture.get();
            if (result.getExitCode() != 0) {
                throw new Exception(result.getOutput());
            }
            output += result.getOutput();
        }

        output = output.replace("sift_pred", "sift_prediction");
        output = output.replace("polyphen_humvar_pred", "polyphen_prediction");
        output = output.replace("polyphen_humvar_score", "polyphen_score");
        return "[" + output.replace("\n{", ",{") + "]";
    }

    private List<List<String>> getVariantChunks(List<String> variants, int chunkSize) {
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

    private List<List<String>> getVariantChunksByChromosome(List<String> variants) {
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

    private int getVEPVersion() throws Exception {
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

    private Callable<VEPResult> runVEP(List<String> flags) {
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

                    exitCode = process.waitFor(); 
                    if (exitCode == 0) {
                        output = outputBuilder.toString();
                    } else {
                        output = errorBuilder.toString();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } 

                return new VEPResult(output, exitCode);
            }
        };
    }
}