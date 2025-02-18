package org.genomenexus.vep_wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
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
public class VepServiceTest {

    @MockitoSpyBean
    private VEPService vepService;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    final Map<String, List<String>> HGVS_PAYLOAD = Map.ofEntries(
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

    @BeforeEach
    public void setup() throws Exception {
        Answer<Callable<VEPResult>> answer = new Answer<>() {
            @Override
            public Callable<VEPResult> answer(InvocationOnMock invocation) throws Throwable {
                List<String> flags = invocation.getArgument(0);
                String flagPrefix = "--input_data=";
                List<String> variants = Arrays.asList(
                    flags.stream()
                        .filter(flag -> flag.startsWith(flagPrefix))
                        .collect(Collectors.toList())
                        .getFirst()
                        .substring(flagPrefix.length())
                        .split("\n")
                );
                return constructMockedResponse(variants);
            }
        };
        Mockito.when(vepService.runVEP(Mockito.anyList())).thenAnswer(answer);
    }

    @Test
    void testAllVariantsAnnotated() throws Exception {
        ResponseEntity<List<Map<String, Object>>> annotatedVariants = restTemplate.exchange(
            "http://localhost:" + port + "/vep/human/hgvs",
            HttpMethod.POST,
            new HttpEntity<Map<String, List<String>>>(HGVS_PAYLOAD), 
            new ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        Map<String, Integer> inputVariantCounts = new HashMap<>();
        for (String variant : HGVS_PAYLOAD.get("hgvs_notations")) {
            inputVariantCounts.put(variant, inputVariantCounts.getOrDefault(variant, 0) + 1);
        }
        Map<String, Integer> outputVariantCounts = new HashMap<>();
        for(Map<String, Object> annotatedVariant : annotatedVariants.getBody()) {
            String originalInput = annotatedVariant.get("input").toString();
            outputVariantCounts.put(originalInput, outputVariantCounts.getOrDefault(originalInput, 0) + 1);
        }

        for (String variant : HGVS_PAYLOAD.get("hgvs_notations")) {
            Integer inputVariantCount = inputVariantCounts.get(variant);
            Integer outputVariantCount = outputVariantCounts.get(variant);
            assertEquals(inputVariantCount, outputVariantCount,
             "Expected " + inputVariantCount + " annotations for " + variant + " but received " + outputVariantCount);
        }
    }

    private Callable<VEPResult> constructMockedResponse(List<String> variants) throws IOException {
        StringBuilder response = new StringBuilder();
        for (String variant : variants) {
            response.append(readMockVariantDataFromFile(variant) + "\n");
        }

        return new Callable<VEPResult>() {
            @Override
            public VEPResult call() throws Exception {
                return new VEPResult(response.toString(), 0);
            }
        };
    }

    private String readMockVariantDataFromFile(String variant) throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        variant = variant.replace(":", "_").replace(">", "-");
        return Files.readString(Paths.get(classLoader.getResource("mock-vep-data/" + variant + ".json").getFile()));
    }
}