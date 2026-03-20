package org.genomenexus.vep_wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VepServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> CACHE_REGION_INPUTS = Map.ofEntries(
        new SimpleEntry<>("7:g.55249071C>T", "7:55249071-55249071:1/T"),
        new SimpleEntry<>("7:g.140453136A>T", "7:140453136-140453136:1/T"),
        new SimpleEntry<>("17:g.7578503_7578518del", "17:7578503-7578518:1/-"),
        new SimpleEntry<>("14:g.81422178C>A", "14:81422178-81422178:1/A"),
        new SimpleEntry<>("10:g.8115874_8115875insG", "10:8115875-8115874:1/G"),
        new SimpleEntry<>("22:g.30032866G>T", "22:30032866-30032866:1/T"),
        new SimpleEntry<>("1:g.11303249C>G", "1:11303249-11303249:1/G"),
        new SimpleEntry<>("19:g.11123705G>C", "19:11123705-11123705:1/C"),
        new SimpleEntry<>("2:g.29474101C>G", "2:29474101-29474101:1/G"),
        new SimpleEntry<>("4:g.187584540C>G", "4:187584540-187584540:1/G"),
        new SimpleEntry<>("5:g.38959383G>C", "5:38959383-38959383:1/C"),
        new SimpleEntry<>("9:g.37020683_37020684delinsTT", "9:37020683-37020684:1/TT")
    );
    private static final List<String> TRANSCRIPT_VARIANTS = List.of(
        "ENST00000269305.4:c.817C>T",
        "ENST00000399503.3:c.2816C>G",
        "ENST00000262189.6:c.2436G>A",
        "ENST00000346208.3:c.1221dup",
        "ENST00000358026.2:c.3480del",
        "ENST00000288135.5:c.2347C>G",
        "ENST00000267163.4:c.1312del",
        "ENST00000263253.7:c.1622+1G>C",
        "ENST00000263923.4:c.1620G>C"
    );
    private static final Map<String, List<String>> HGVS_PAYLOAD = Map.ofEntries(
        new SimpleEntry<>("hgvs_notations", List.of(
            "7:g.55249071C>T",
            "7:g.140453136A>T",
            "ENST00000269305.4:c.817C>T",
            "17:g.7578503_7578518del",
            "ENST00000399503.3:c.2816C>G",
            "ENST00000262189.6:c.2436G>A",
            "14:g.81422178C>A",
            "ENST00000346208.3:c.1221dup",
            "10:g.8115874_8115875insG",
            "22:g.30032866G>T",
            "ENST00000358026.2:c.3480del",
            "1:g.11303249C>G",
            "ENST00000288135.5:c.2347C>G",
            "ENST00000267163.4:c.1312del",
            "ENST00000263253.7:c.1622+1G>C",
            "19:g.11123705G>C",
            "2:g.29474101C>G",
            "4:g.187584540C>G",
            "ENST00000263923.4:c.1620G>C",
            "5:g.38959383G>C",
            "9:g.37020683_37020684delinsTT",
            "7:g.55249071C>T"
        ))
    );

    private final List<List<String>> recordedFlags = Collections.synchronizedList(new ArrayList<>());

    private TestableVEPService vepService;
    private VEPConfiguration vepConfiguration;

    @BeforeEach
    void setup() {
        recordedFlags.clear();
        vepConfiguration = new VEPConfiguration();
        vepConfiguration.setForks(0);
        vepConfiguration.setHgvsMaxThreads(75);
        vepConfiguration.setHost("");
        vepConfiguration.setPort(0);
        vepConfiguration.setUsername("");
        vepConfiguration.setPassword("");
        vepConfiguration.setDataSourceMode(VEPConfiguration.DATA_SOURCE_MODE_DATABASE);

        vepService = new TestableVEPService();
        vepService.setVepConfiguration(vepConfiguration);
    }

    @Test
    void testDatabaseModeKeepsHgvsRouting() throws Exception {
        List<Map<String, Object>> annotatedVariants = readAnnotatedVariants(
            vepService.annotateHgvsVariants(HGVS_PAYLOAD.get("hgvs_notations"))
        );

        assertDuplicateCountsMatch(annotatedVariants);
        assertTrue(recordedFlags.stream().allMatch(flags -> flags.contains("--database")));
        assertTrue(recordedFlags.stream().noneMatch(flags -> flags.contains("--cache")));
        assertTrue(recordedFlags.stream().allMatch(flags -> flags.contains("--format=hgvs")));
    }

    @Test
    void testCacheModeSplitsTranscriptAndGenomicHgvsRequests() throws Exception {
        vepConfiguration.setDataSourceMode(VEPConfiguration.DATA_SOURCE_MODE_CACHE);

        List<Map<String, Object>> annotatedVariants = readAnnotatedVariants(
            vepService.annotateHgvsVariants(HGVS_PAYLOAD.get("hgvs_notations"))
        );

        assertDuplicateCountsMatch(annotatedVariants);
        assertTrue(recordedFlags.stream().allMatch(flags -> flags.contains("--cache")));
        assertTrue(recordedFlags.stream().noneMatch(flags -> flags.contains("--database")));

        List<String> hgvsInputs = flattenRecordedInputsByFormat("hgvs");
        List<String> regionInputs = flattenRecordedInputsByFormat("region");

        assertIterableEquals(TRANSCRIPT_VARIANTS.stream().sorted().toList(), hgvsInputs.stream().sorted().toList());
        assertIterableEquals(expectedRegionInputs().stream().sorted().toList(), regionInputs.stream().sorted().toList());
    }

    @Test
    void testCacheModeConvertsSupportedGenomicHgvsInputs() {
        vepConfiguration.setDataSourceMode(VEPConfiguration.DATA_SOURCE_MODE_CACHE);

        assertEquals("7:55249071-55249071:1/T", vepService.normalizeHgvsVariant("7:g.55249071C>T").vepInput());
        assertEquals("17:7578503-7578518:1/-", vepService.normalizeHgvsVariant("17:g.7578503_7578518del").vepInput());
        assertEquals("10:8115875-8115874:1/G", vepService.normalizeHgvsVariant("10:g.8115874_8115875insG").vepInput());
        assertEquals("9:37020683-37020684:1/TT", vepService.normalizeHgvsVariant("9:g.37020683_37020684delinsTT").vepInput());
    }

    @Test
    void testCacheModeRejectsUnsupportedGenomicHgvs() {
        vepConfiguration.setDataSourceMode(VEPConfiguration.DATA_SOURCE_MODE_CACHE);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> vepService.annotateHgvsVariants(List.of("7:g.55249071dup"))
        );

        assertEquals("Unsupported genomic HGVS input for cache mode: 7:g.55249071dup", exception.getMessage());
    }

    private void assertDuplicateCountsMatch(List<Map<String, Object>> annotatedVariants) {
        Map<String, Integer> inputVariantCounts = new HashMap<>();
        for (String variant : HGVS_PAYLOAD.get("hgvs_notations")) {
            inputVariantCounts.put(variant, inputVariantCounts.getOrDefault(variant, 0) + 1);
        }

        Map<String, Integer> outputVariantCounts = new HashMap<>();
        for (Map<String, Object> annotatedVariant : annotatedVariants) {
            String originalInput = annotatedVariant.get("input").toString();
            outputVariantCounts.put(originalInput, outputVariantCounts.getOrDefault(originalInput, 0) + 1);
        }

        for (String variant : HGVS_PAYLOAD.get("hgvs_notations")) {
            Integer inputVariantCount = inputVariantCounts.get(variant);
            Integer outputVariantCount = outputVariantCounts.get(variant);
            assertEquals(
                inputVariantCount,
                outputVariantCount,
                "Expected " + inputVariantCount + " annotations for " + variant + " but received " + outputVariantCount
            );
        }
    }

    private List<String> flattenRecordedInputsByFormat(String format) {
        return recordedFlags.stream()
            .filter(flags -> flags.contains("--format=" + format))
            .flatMap(flags -> Arrays.stream(getFlagValue(flags, "--input_data=").split("\n")))
            .sorted()
            .toList();
    }

    private List<String> expectedRegionInputs() {
        return HGVS_PAYLOAD.get("hgvs_notations").stream()
            .filter(CACHE_REGION_INPUTS::containsKey)
            .map(CACHE_REGION_INPUTS::get)
            .toList();
    }

    private List<Map<String, Object>> readAnnotatedVariants(String json) throws Exception {
        return OBJECT_MAPPER.readValue(
            json,
            OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class)
        );
    }

    private String readMockVariantDataFromFile(String variant) throws IOException {
        String originalVariant = resolveOriginalVariant(variant);
        ClassLoader classLoader = getClass().getClassLoader();
        String resourceName = originalVariant.replace(":", "_").replace(">", "-");
        String json = Files.readString(Paths.get(classLoader.getResource("mock-vep-data/" + resourceName + ".json").getFile()));
        ObjectNode jsonNode = (ObjectNode)OBJECT_MAPPER.readTree(json);
        if (!originalVariant.equals(variant)) {
            jsonNode.put("input", variant);
        }
        return OBJECT_MAPPER.writeValueAsString(jsonNode);
    }

    private String resolveOriginalVariant(String variant) {
        for (Map.Entry<String, String> entry : CACHE_REGION_INPUTS.entrySet()) {
            if (entry.getValue().equals(variant)) {
                return entry.getKey();
            }
        }
        return variant;
    }

    private String getFlagValue(List<String> flags, String prefix) {
        return flags.stream()
            .filter(flag -> flag.startsWith(prefix))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing flag: " + prefix))
            .substring(prefix.length());
    }

    private class TestableVEPService extends VEPService {
        @Override
        public Callable<VEPResult> runVEP(List<String> flags) {
            List<String> flagCopy = new ArrayList<>(flags);
            recordedFlags.add(flagCopy);

            List<String> variants = Arrays.asList(getFlagValue(flagCopy, "--input_data=").split("\n"));
            StringBuilder response = new StringBuilder();
            for (String variant : variants) {
                try {
                    response.append(readMockVariantDataFromFile(variant)).append("\n");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            return () -> new VEPResult(response.toString(), 0);
        }
    }
}
