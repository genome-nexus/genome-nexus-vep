package org.genomenexus.vep_wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Tests that VEP error messages are properly formatted for different error types.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class VepErrorMessageTest {

    @MockitoSpyBean
    private VEPService vepService;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // Simulated VEP error messages (as they appear in stderr)
    private static final String REF_ALLELE_MISMATCH_ERROR =
        "Unable to parse HGVS notation '1:g.1020385C>A'Reference allele extracted from " +
        "1:1020385-1020385 (T) does not match reference allele given by HGVS notation 1:g.1020385C>A (C)";

    private static final String COULD_NOT_PARSE_ERROR =
        "Unable to parse HGVS notation 'invalid_notation'Could not parse the HGVS notation invalid_notation";

    private static final String NOT_FOUND_ERROR =
        "Unable to parse HGVS notation '99:g.100A>T'Could not find chromosome 99 not found in database";

    private static final String UNKNOWN_ERROR =
        "Something completely unexpected happened during annotation";

    @BeforeEach
    public void setup() throws Exception {
        Answer<Callable<VEPResult>> answer = new Answer<>() {
            @Override
            public Callable<VEPResult> answer(InvocationOnMock invocation) throws Throwable {
                List<String> flags = invocation.getArgument(0);
                String flagPrefix = "--input_data=";
                String inputData = flags.stream()
                    .filter(flag -> flag.startsWith(flagPrefix))
                    .findFirst()
                    .map(flag -> flag.substring(flagPrefix.length()))
                    .orElse("");

                List<String> variants = Arrays.asList(inputData.split("\n"));
                return constructMockedResponse(variants);
            }
        };
        Mockito.when(vepService.runVEP(Mockito.anyList())).thenAnswer(answer);
    }

    @Test
    void testRefAlleleMismatchError() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("1:g.1020385C>A"))
        );

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(1, body.size());

        Map<String, Object> errorResult = body.get(0);
        assertEquals(false, errorResult.get("successfully_annotated"));
        String error = errorResult.get("error").toString();
        // Should contain variant ID and clear mismatch message
        assertTrue(error.contains("1:g.1020385C>A"),
            "Expected variant identifier in error, got: " + error);
        assertTrue(error.contains("Reference allele extracted from input (C) does not match reference allele from genome (T)"),
            "Expected ref allele mismatch details, got: " + error);
    }

    @Test
    void testCouldNotParseError() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("invalid_notation"))
        );

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(1, body.size());

        Map<String, Object> errorResult = body.get(0);
        assertEquals(false, errorResult.get("successfully_annotated"));
        String error = errorResult.get("error").toString();
        assertTrue(error.contains("Invalid HGVS notation"),
            "Expected 'Invalid HGVS notation' message, got: " + error);
        assertTrue(error.contains("invalid_notation"),
            "Expected variant in error message, got: " + error);
    }

    @Test
    void testChromosomeNotFoundError() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("99:g.100A>T"))
        );

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(1, body.size());

        Map<String, Object> errorResult = body.get(0);
        assertEquals(false, errorResult.get("successfully_annotated"));
        String error = errorResult.get("error").toString();
        assertTrue(error.contains("Chromosome or position not found"),
            "Expected chromosome not found message, got: " + error);
        assertTrue(error.contains("99:g.100A>T"),
            "Expected variant in error message, got: " + error);
    }

    @Test
    void testUnknownError() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("unknown_error_variant"))
        );

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(1, body.size());

        Map<String, Object> errorResult = body.get(0);
        assertEquals(false, errorResult.get("successfully_annotated"));
        String error = errorResult.get("error").toString();
        assertTrue(error.contains("Error annotating variant"),
            "Expected fallback error message, got: " + error);
        assertTrue(error.contains("Something completely unexpected"),
            "Expected raw error in fallback, got: " + error);
    }

    @Test
    void testMixedBatchWithBadAndGoodVariants() throws Exception {
        // Mix of one bad (ref mismatch) and one good variant
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("1:g.1020385C>A", "7:g.55249071C>T"))
        );

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<>(payload),
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> body = response.getBody();
        assertEquals(2, body.size());

        // Find the error and success entries
        Map<String, Object> errorEntry = null;
        Map<String, Object> successEntry = null;
        for (Map<String, Object> entry : body) {
            if (Boolean.FALSE.equals(entry.get("successfully_annotated"))) {
                errorEntry = entry;
            } else if (entry.containsKey("input") && "7:g.55249071C>T".equals(entry.get("input"))) {
                successEntry = entry;
            }
        }

        // Bad variant should have ref allele mismatch error
        assertTrue(errorEntry != null, "Expected an error entry for the bad variant");
        String error = errorEntry.get("error").toString();
        assertTrue(error.contains("1:g.1020385C>A"),
            "Expected variant identifier in ref mismatch error, got: " + error);
        assertTrue(error.contains("does not match reference allele from genome"),
            "Expected ref mismatch error, got: " + error);

        // Good variant should be successfully annotated (present in output)
        // It won't have "successfully_annotated" field — it's a normal VEP JSON annotation
        boolean hasGoodVariantAnnotation = body.stream()
            .anyMatch(entry -> !Boolean.FALSE.equals(entry.get("successfully_annotated"))
                && entry.containsKey("input")
                && "7:g.55249071C>T".equals(entry.get("input")));
        assertTrue(hasGoodVariantAnnotation,
            "Expected the good variant (7:g.55249071C>T) to be successfully annotated");
    }

    /**
     * Construct mocked VEP responses. Variants that match known error patterns
     * return error results (non-zero exit code); others return mock annotation data.
     */
    private Callable<VEPResult> constructMockedResponse(List<String> variants) {
        // Check if any variant should trigger an error
        for (String variant : variants) {
            if (variant.contains("1:g.1020385C>A")) {
                return () -> new VEPResult("", REF_ALLELE_MISMATCH_ERROR);
            }
            if (variant.contains("invalid_notation")) {
                return () -> new VEPResult("", COULD_NOT_PARSE_ERROR);
            }
            if (variant.contains("99:g.100A>T")) {
                return () -> new VEPResult("", NOT_FOUND_ERROR);
            }
            if (variant.contains("unknown_error_variant")) {
                return () -> new VEPResult("", UNKNOWN_ERROR);
            }
        }

        // All variants are good — return success with mock annotation JSON
        StringBuilder response = new StringBuilder();
        for (String variant : variants) {
            try {
                response.append(readMockVariantDataFromFile(variant)).append("\n");
            } catch (IOException e) {
                // If no mock file exists, return a minimal valid JSON
                response.append(String.format(
                    "{\"input\":\"%s\",\"most_severe_consequence\":\"missense_variant\"}\n", variant));
            }
        }
        String output = response.toString();
        return () -> new VEPResult(output, "");
    }

    private String readMockVariantDataFromFile(String variant) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        String filename = variant.replace(":", "_").replace(">", "-");
        return Files.readString(Paths.get(classLoader.getResource("mock-vep-data/" + filename + ".json").getFile()));
    }
}
