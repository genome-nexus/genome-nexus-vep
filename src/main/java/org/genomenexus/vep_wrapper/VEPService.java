package org.genomenexus.vep_wrapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class VEPService {
    private static final Pattern GENOMIC_SUBSTITUTION_PATTERN = Pattern.compile(
        "^(?:chr)?(?<chromosome>[0-9]+|X|Y|M|MT):g\\.(?<position>[0-9]+)(?<reference>[A-Z]+)>(?<alternate>[A-Z]+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENOMIC_DELETION_PATTERN = Pattern.compile(
        "^(?:chr)?(?<chromosome>[0-9]+|X|Y|M|MT):g\\.(?<start>[0-9]+)(?:_(?<end>[0-9]+))?del$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENOMIC_INSERTION_PATTERN = Pattern.compile(
        "^(?:chr)?(?<chromosome>[0-9]+|X|Y|M|MT):g\\.(?<left>[0-9]+)_(?<right>[0-9]+)ins(?<sequence>[A-Z]+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENOMIC_DELINS_PATTERN = Pattern.compile(
        "^(?:chr)?(?<chromosome>[0-9]+|X|Y|M|MT):g\\.(?<start>[0-9]+)_(?<end>[0-9]+)delins(?<sequence>[A-Z]+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GENOMIC_HGVS_PREFIX_PATTERN = Pattern.compile(
        "^(?:chr)?(?:[0-9]+|X|Y|M|MT):g\\.",
        Pattern.CASE_INSENSITIVE
    );
    private static final List<String> CHROMOSOME_ORDER = List.of(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
        "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "X", "Y", "MT"
    );

    @Autowired
    private VEPConfiguration vepConfiguration;

    private final ObjectMapper objectMapper = new ObjectMapper();

    void setVepConfiguration(VEPConfiguration vepConfiguration) {
        this.vepConfiguration = vepConfiguration;
    }

    public String annotateVariants(List<List<String>> variantChunks, String format) throws Exception {
        List<VEPBatch> batches = variantChunks.stream()
            .map(chunk -> new VEPBatch(
                format,
                chunk.stream().map(variant -> new NormalizedVariant(variant, variant, format)).toList()
            ))
            .toList();
        return executeBatches(batches);
    }

    public String annotateHgvsVariants(List<String> variants) throws Exception {
        if (!vepConfiguration.isCacheMode()) {
            return annotateVariants(getVariantChunks(variants, 1), "hgvs");
        }

        List<NormalizedVariant> transcriptVariants = new ArrayList<>();
        List<NormalizedVariant> genomicVariants = new ArrayList<>();
        for (String variant : variants) {
            NormalizedVariant normalizedVariant = normalizeHgvsVariant(variant);
            if ("region".equals(normalizedVariant.format())) {
                genomicVariants.add(normalizedVariant);
            } else {
                transcriptVariants.add(normalizedVariant);
            }
        }

        List<VEPBatch> batches = new ArrayList<>();
        addBatches(batches, transcriptVariants, "hgvs", false);
        addBatches(batches, genomicVariants, "region", true);
        return executeBatches(batches);
    }

    private void addBatches(
        List<VEPBatch> batches,
        List<NormalizedVariant> variants,
        String format,
        boolean chunkByChromosome
    ) {
        if (variants.isEmpty()) {
            return;
        }

        Function<String, NormalizedVariant> variantMatcher = createVariantMatcher(variants);
        List<String> vepInputs = variants.stream().map(NormalizedVariant::vepInput).toList();
        List<List<String>> chunks = chunkByChromosome
            ? getVariantChunksByChromosome(vepInputs)
            : getVariantChunks(vepInputs, 1);
        for (List<String> chunk : chunks) {
            batches.add(new VEPBatch(format, chunk.stream().map(variantMatcher).toList()));
        }
    }

    private Function<String, NormalizedVariant> createVariantMatcher(List<NormalizedVariant> variants) {
        List<NormalizedVariant> remainingVariants = new ArrayList<>(variants);
        return vepInput -> {
            for (int i = 0; i < remainingVariants.size(); i++) {
                NormalizedVariant candidate = remainingVariants.get(i);
                if (candidate.vepInput().equals(vepInput)) {
                    remainingVariants.remove(i);
                    return candidate;
                }
            }
            throw new IllegalStateException("Unable to match normalized VEP input: " + vepInput);
        };
    }

    private String executeBatches(List<VEPBatch> batches) throws Exception {
        List<Callable<VEPResult>> wrappers = new ArrayList<>();
        for (VEPBatch batch : batches) {
            wrappers.add(runVEP(buildFlags(batch)));
        }

        String output = "";
        ExecutorService threadPool = Executors.newCachedThreadPool();
        List<Future<VEPResult>> resultFutures = threadPool.invokeAll(wrappers);

        Exception exception = null;
        boolean allFailed = true;
        for (int i = 0; i < resultFutures.size(); i++) {
            Future<VEPResult> resultFuture = resultFutures.get(i);
            VEPResult result = resultFuture.get();
            if (result.getExitCode() == 0) {
                output += rewriteOutputInputs(result.getOutput(), batches.get(i).variants());
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

    private List<String> buildFlags(VEPBatch batch) {
        List<String> flags = new ArrayList<>();
        addModeSpecificFlags(flags);
        Collections.addAll(flags,
            "--fork=" + vepConfiguration.getForks(),
            "--format=" + batch.format(),
            "--input_data=" + batch.variants().stream().map(NormalizedVariant::vepInput).collect(Collectors.joining("\n")),
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
        return flags;
    }

    private void addModeSpecificFlags(List<String> flags) {
        if (vepConfiguration.isCacheMode()) {
            flags.add("--cache");
            return;
        }

        Collections.addAll(flags,
            "--database",
            "--host=" + vepConfiguration.getHost(),
            "--port=" + vepConfiguration.getPort(),
            "--user=" + vepConfiguration.getUsername(),
            "--password=" + vepConfiguration.getPassword()
        );
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
        Map<String, List<String>> variantsByChromosome = new LinkedHashMap<>();
        for (String chromosome : CHROMOSOME_ORDER) {
            variantsByChromosome.put(chromosome, new ArrayList<>());
        }
        for (String variant : variants) {
            String chromosome = normalizeChromosome(variant.split(":")[0]);
            List<String> chromosomeVariants = variantsByChromosome.get(chromosome);
            if (chromosomeVariants == null) {
                throw new IllegalArgumentException("Unsupported chromosome for region input: " + chromosome);
            }
            chromosomeVariants.add(variant);
        }
        return variantsByChromosome.values().stream().filter(chunk -> !chunk.isEmpty()).collect(Collectors.toList());
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

    NormalizedVariant normalizeHgvsVariant(String variant) {
        Matcher matcher = GENOMIC_SUBSTITUTION_PATTERN.matcher(variant);
        if (matcher.matches()) {
            String chromosome = normalizeChromosome(matcher.group("chromosome"));
            String position = matcher.group("position");
            String alternate = matcher.group("alternate").toUpperCase();
            return new NormalizedVariant(variant, chromosome + ":" + position + "-" + position + ":1/" + alternate, "region");
        }

        matcher = GENOMIC_DELINS_PATTERN.matcher(variant);
        if (matcher.matches()) {
            String chromosome = normalizeChromosome(matcher.group("chromosome"));
            String start = matcher.group("start");
            String end = matcher.group("end");
            String sequence = matcher.group("sequence").toUpperCase();
            return new NormalizedVariant(variant, chromosome + ":" + start + "-" + end + ":1/" + sequence, "region");
        }

        matcher = GENOMIC_DELETION_PATTERN.matcher(variant);
        if (matcher.matches()) {
            String chromosome = normalizeChromosome(matcher.group("chromosome"));
            String start = matcher.group("start");
            String end = matcher.group("end") == null ? start : matcher.group("end");
            return new NormalizedVariant(variant, chromosome + ":" + start + "-" + end + ":1/-", "region");
        }

        matcher = GENOMIC_INSERTION_PATTERN.matcher(variant);
        if (matcher.matches()) {
            String chromosome = normalizeChromosome(matcher.group("chromosome"));
            int left = Integer.parseInt(matcher.group("left"));
            int right = Integer.parseInt(matcher.group("right"));
            if (right != left + 1) {
                throw new IllegalArgumentException("Unsupported genomic HGVS insertion coordinates: " + variant);
            }
            String sequence = matcher.group("sequence").toUpperCase();
            return new NormalizedVariant(variant, chromosome + ":" + right + "-" + left + ":1/" + sequence, "region");
        }

        if (GENOMIC_HGVS_PREFIX_PATTERN.matcher(variant).find()) {
            throw new IllegalArgumentException("Unsupported genomic HGVS input for cache mode: " + variant);
        }
        return new NormalizedVariant(variant, variant, "hgvs");
    }

    private String rewriteOutputInputs(String output, List<NormalizedVariant> variants) throws IOException {
        if (!StringUtils.hasText(output)) {
            return output;
        }

        StringBuilder rewrittenOutput = new StringBuilder();
        String[] outputLines = output.split("\\R");
        int variantIndex = 0;
        for (String line : outputLines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }

            String rewrittenLine = line;
            if (variantIndex < variants.size()) {
                ObjectNode outputNode = (ObjectNode)objectMapper.readTree(line);
                outputNode.put("input", variants.get(variantIndex).originalInput());
                rewrittenLine = objectMapper.writeValueAsString(outputNode);
                variantIndex++;
            }
            rewrittenOutput.append(rewrittenLine).append(System.lineSeparator());
        }
        return rewrittenOutput.toString();
    }

    private String normalizeChromosome(String chromosome) {
        String normalizedChromosome = chromosome.trim().toUpperCase();
        if (normalizedChromosome.startsWith("CHR")) {
            normalizedChromosome = normalizedChromosome.substring(3);
        }
        if ("M".equals(normalizedChromosome)) {
            return "MT";
        }
        return normalizedChromosome;
    }

    record VEPBatch(String format, List<NormalizedVariant> variants) {}

    record NormalizedVariant(String originalInput, String vepInput, String format) {}
}
