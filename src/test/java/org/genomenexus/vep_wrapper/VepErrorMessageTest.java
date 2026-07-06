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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class VepErrorMessageTest {

    @MockitoSpyBean
    private VEPService vepService;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static final String MOCK_STDERR = "WARNING: annotation failed for bad_variant";

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
    void testSingleFailedVariant() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("bad_variant"))
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
        assertTrue(((String) errorResult.get("error")).length() > 0,
            "Expected non-empty error message");
    }

    @Test
    void testMixedBatchWithBadAndGoodVariants() throws Exception {
        Map<String, List<String>> payload = Map.ofEntries(
            new SimpleEntry<>("hgvs_notations", List.of("bad_variant", "7:g.55249071C>T"))
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

        Map<String, Object> errorEntry = body.stream()
            .filter(e -> Boolean.FALSE.equals(e.get("successfully_annotated")))
            .findFirst().orElse(null);
        assertTrue(errorEntry != null, "Expected an error entry for the bad variant");
        assertTrue(((String) errorEntry.get("error")).length() > 0,
            "Expected non-empty error message");

        boolean hasGoodVariant = body.stream()
            .anyMatch(e -> !Boolean.FALSE.equals(e.get("successfully_annotated"))
                && e.containsKey("input"));
        assertTrue(hasGoodVariant, "Expected the good variant to be successfully annotated");
    }

    private Callable<VEPResult> constructMockedResponse(List<String> variants) {
        StringBuilder output = new StringBuilder();
        boolean hasBadVariant = false;
        for (String variant : variants) {
            if (variant.contains("bad_variant")) {
                hasBadVariant = true;
            } else {
                try {
                    output.append(readMockVariantDataFromFile(variant)).append("\n");
                } catch (IOException e) {
                    output.append(String.format(
                        "{\"input\":\"%s\",\"most_severe_consequence\":\"missense_variant\"}\n", variant));
                }
            }
        }
        String stdout = output.toString();
        String stderr = hasBadVariant ? MOCK_STDERR : "";
        return () -> new VEPResult(stdout, stderr);
    }

    private String readMockVariantDataFromFile(String variant) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        String filename = variant.replace(":", "_").replace(">", "-");
        return Files.readString(Paths.get(classLoader.getResource("mock-vep-data/" + filename + ".json").getFile()));
    }
}
