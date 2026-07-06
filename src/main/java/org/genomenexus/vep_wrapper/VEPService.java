package org.genomenexus.vep_wrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Service
public class VEPService {

    private static final Pattern VEP_VERSION_PATTERN = Pattern.compile("ensembl-vep\\s*:\\s*(\\d+)");
    private static final Pattern VEP_FAILED_HGVS_PATTERN = Pattern.compile("HGVS notation '([^']+)'");
    private static final Pattern VEP_FAILED_REGION_PATTERN = Pattern.compile("\\+ (\\S+)$", Pattern.MULTILINE);
    private static final Pattern VEP_OUTPUT_INPUT_PATTERN = Pattern.compile("\"input\":\"([^\"]+)\"");
    private static final Pattern REF_ALLELE_MISMATCH_PATTERN = Pattern.compile(
        "Reference allele extracted from \\S+ \\(([^)]+)\\) does not match reference allele given by HGVS notation (\\S+) \\(([^)]+)\\)");
    private static final Pattern COULD_NOT_PARSE_PATTERN = Pattern.compile("Could not parse.*HGVS notation (\\S+)");

    @Autowired
    private VEPConfiguration vepConfiguration;

    private ExecutorService chunkExecutor;
    private volatile Integer cachedVepVersion;

    @PostConstruct
    public void init() {
        this.chunkExecutor = Executors.newFixedThreadPool(vepConfiguration.hgvsMaxThreads);
    }

    @PreDestroy
    public void shutdown() {
        if (chunkExecutor != null) {
            chunkExecutor.shutdownNow();
        }
    }

    public String annotateVariants(List<List<String>> variantChunks, Optional<String> format) throws Exception {
        List<String> flags = buildBaseFlags(format);

        StringBuilder outputBuilder = new StringBuilder();
        List<FailedVariant> failedVariants = new ArrayList<>();

        List<Callable<VEPResult>> wrappers = new ArrayList<>();
        for (List<String> chunk : variantChunks) {
            List<String> chunkFlags = new ArrayList<>(flags);
            chunkFlags.add("--input_data=" + chunk.stream().collect(Collectors.joining("\n")));
            wrappers.add(runVEP(chunkFlags));
        }

        List<Future<VEPResult>> resultFutures = chunkExecutor.invokeAll(wrappers);

        for (int i = 0; i < resultFutures.size(); i++) {
            VEPResult result = resultFutures.get(i).get();
            if (StringUtils.hasText(result.getOutput())) {
                outputBuilder.append(result.getOutput());
            }
            // Find any input variants absent from VEP's output and record them as failures.
            // This catches both the mixed case (VEP exits 0, bad variants silently skipped)
            // and the all-bad case (VEP exits 0 with empty stdout).
            Set<String> annotatedIds = extractAnnotatedIds(result.getOutput());
            for (String input : variantChunks.get(i)) {
                String id = extractVariantIdFromInput(input);
                if (!annotatedIds.contains(id)) {
                    failedVariants.add(new FailedVariant(id, result.getStderr()));
                }
            }
        }

        // Build final JSON array combining successful annotations and error objects
        String output = outputBuilder.toString();
        output = output.replace("sift_pred", "sift_prediction");
        output = output.replace("polyphen_humvar_pred", "polyphen_prediction");
        output = output.replace("polyphen_humvar_score", "polyphen_score");

        StringBuilder result = new StringBuilder();
        if (!output.isEmpty()) {
            result.append(output.replace("\n{", ",{"));
        }
        // Append error objects for failed variants
        for (FailedVariant failed : failedVariants) {
            if (result.length() > 0) {
                result.append(",");
            }
            String detailedError = formatVepErrorMessage(failed.input, failed.error);
            result.append(String.format("{\"input\":\"%s\",\"error\":\"%s\",\"successfully_annotated\":false}",
                escapeJson(failed.input), escapeJson(detailedError)));
        }

        if (result.length() == 0) {
            throw new Exception("All variants failed annotation");
        }

        return "[" + result.toString() + "]";
    }

    /**
     * Extract variant ID from VEP input line.
     * For region format: "1 1020385 1020385 N/A + 1:g.1020385C>A" → "1:g.1020385C>A"
     * For hgvs format: "1:g.1020385C>A" → "1:g.1020385C>A"
     */
    private String extractVariantIdFromInput(String input) {
        Matcher matcher = VEP_FAILED_REGION_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return input.trim();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private Set<String> extractAnnotatedIds(String stdout) {
        Set<String> ids = new HashSet<>();
        if (!StringUtils.hasText(stdout)) return ids;
        for (String line : stdout.split("\n")) {
            Matcher m = VEP_OUTPUT_INPUT_PATTERN.matcher(line);
            if (m.find()) {
                ids.add(extractVariantIdFromInput(m.group(1)));
            }
        }
        return ids;
    }

    /**
     * Format VEP error into a user-facing message.
     */
    private String formatVepErrorMessage(String variantInput, String rawError) {
        if (rawError == null || rawError.isEmpty()) {
            return "Unknown error annotating variant: " + variantInput;
        }

        // Reference allele mismatch — extract alleles from VEP's message
        Matcher refMatcher = REF_ALLELE_MISMATCH_PATTERN.matcher(rawError);
        if (refMatcher.find()) {
            String genomeAllele = refMatcher.group(1);
            String notation = refMatcher.group(2);
            String inputAllele = refMatcher.group(3);
            return String.format(
                "%s: Reference allele extracted from input (%s) does not match reference allele from genome (%s)",
                notation, inputAllele, genomeAllele);
        }

        // Could not parse HGVS notation
        Matcher parseMatcher = COULD_NOT_PARSE_PATTERN.matcher(rawError);
        if (parseMatcher.find()) {
            String notation = parseMatcher.group(1);
            return String.format(
                "Invalid HGVS notation '%s': could not be parsed. ", notation);
        }

        // Contains HGVS notation reference but unknown sub-error
        Matcher hgvsMatcher = VEP_FAILED_HGVS_PATTERN.matcher(rawError);
        if (hgvsMatcher.find()) {
            String notation = hgvsMatcher.group(1);
            if (rawError.contains("not found in database") || rawError.contains("Could not find")
                || rawError.contains("uninitialized value") || rawError.contains("Can't call method")) {
                return String.format(
                    "Chromosome or position not found: variant '%s' - chromosome or position does not exist in the genome assembly.", notation);
            }
        }

        // Fallback: return raw error with variant context
        return String.format("Error annotating variant '%s': %s",
            variantInput, rawError.trim().replace("\n", " "));
    }

    private List<String> buildBaseFlags(Optional<String> format) {
        List<String> flags = new ArrayList<>(Arrays.asList(
            "--output_file=STDOUT",
                "--warning_file=STDERR",
                "--everything",
                "--hgvsg",
                "--no_stats",
                "--xref_refseq",
                "--json",
                "--shift_hgvs=1",
                "--fork=" + vepConfiguration.forks
        ));
        if (format.isPresent()) { // vep breaks when the format is set to ensembl (even though it should be correct)
            flags.add("--format=" + format.get());
        }
        switch (vepConfiguration.dataConfiguration) {
			case VEPConfiguration.DatabaseConfiguration(int port, String host, String username, String password) -> {
                Collections.addAll(
                    flags,
                    "--database",
                    "--host=" + host,
                    "--port=" + port,
                    "--user=" + username,
                    "--password=" + password
                );
            }
			case VEPConfiguration.CacheConfiguration(String fastaFilename) -> {
                Collections.addAll(
                    flags,
                    "--cache",
                    "--dir_cache=/cache-data",
                    "--fasta=/cache-data/" + fastaFilename,
                    "--lookup_ref",
                    "--offline"
                );
            }
        }
        if (vepConfiguration.polyphenSiftFilename.isPresent() || vepConfiguration.alphaMissenseFilename.isPresent()) {
            flags.add("--dir_plugins=/plugin-data");
        }
        if (vepConfiguration.polyphenSiftFilename.isPresent()) {
            flags.add("--plugin=PolyPhen_SIFT,db=/plugin-data/" + vepConfiguration.polyphenSiftFilename.get());
        }
        if (vepConfiguration.alphaMissenseFilename.isPresent()) {
            flags.add("--plugin=AlphaMissense,file=/plugin-data/" + vepConfiguration.alphaMissenseFilename.get());
        }
        return flags;
    }

    private record FailedVariant(String input, String error) {}

    public List<List<String>> getVariantChunks(List<String> variants, int chunkSize) {
        List<List<String>> variantChunks = new ArrayList<>();
        int numVariants = variants.size();
        int maxThreads = vepConfiguration.hgvsMaxThreads;

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
        Integer cached = cachedVepVersion;
        if (cached != null) {
            return cached;
        }
        VEPResult result = runVEP(new ArrayList<>()).call();
        Matcher matcher = VEP_VERSION_PATTERN.matcher(result.getOutput() + result.getStderr());
        if (matcher.find()) {
            int version = Integer.parseInt(matcher.group(1));
            cachedVepVersion = version;
            return version;
        } else {
            throw new Exception("Version not found in VEP output");
        }
    }

    public Callable<VEPResult> runVEP(List<String> flags) {
        return new Callable<VEPResult>() {
            @Override
            public VEPResult call() throws Exception {
                String path = Paths.get("").toAbsolutePath().toString() + "/scripts/vep";
                String lineSeparator = System.lineSeparator();

                StringBuilder outputBuilder = new StringBuilder();
                StringBuilder errorBuilder = new StringBuilder();
                try {
                    flags.add(0, path);
                    Process process = new ProcessBuilder().command(flags).start();

                    // Drain stderr on a separate thread so a full stderr pipe buffer
                    // cannot deadlock VEP while we are still reading stdout.
                    Thread stderrReader = new Thread(() -> {
                        try (BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                            String errLine;
                            while ((errLine = stderr.readLine()) != null) {
                                errorBuilder.append(errLine).append(lineSeparator);
                            }
                        } catch (IOException ignored) {
                        }
                    }, "vep-stderr-reader");
                    stderrReader.setDaemon(true);
                    stderrReader.start();

                    try (BufferedReader stdin = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = stdin.readLine()) != null) {
                            outputBuilder.append(line).append(lineSeparator);
                        }
                    }
                    stderrReader.join();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return new VEPResult(outputBuilder.toString(), errorBuilder.toString());
            }
        };
    }
}
