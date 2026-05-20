package org.genomenexus.vep_wrapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private VEPService vepService;

    @Autowired
    private VEPConfiguration vepConfiguration;

    @GetMapping("/vep/human/hgvs/{variant}")
    public ResponseEntity<Object> annotateHGVS(@PathVariable String variant) {
        Optional<String> format = Optional.of("hgvs");
        if (vepConfiguration.mode == VEPConfiguration.Mode.Cache) {
            try {
                variant = hgvsgToEnsembl(variant);
                format = Optional.empty();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(constructErrorMessage(e));
            }
        }

        List<List<String>> variantChunks = new ArrayList<>();
        variantChunks.add(Arrays.asList(variant));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, format));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @PostMapping("/vep/human/hgvs")
    public ResponseEntity<Object> annotateHGVS(@RequestBody Map<String, List<String>> variants) {
        List<String> variantList = variants.get("hgvs_notations");
        if (variantList == null) {
            return ResponseEntity.badRequest().body(("Missing key: 'hgvs_notations'"));
        }

        Optional<String> format = Optional.of("hgvs");
        List<String> errors = new ArrayList<>();
        if (vepConfiguration.mode == VEPConfiguration.Mode.Cache) {
            format = Optional.empty();
            for (int i = 0; i < variantList.size(); i++) {
                try {
                     variantList.set(i, hgvsgToEnsembl(variantList.get(i)));
                } catch (IllegalArgumentException e) {
                     errors.add(e.getMessage());
                }
            }
        }

        if (!errors.isEmpty()) {
            Map<String, Object> body = new HashMap<>(constructErrorMessage(new Exception("Could not annotate variants")));
            body.put("details", errors);
            return ResponseEntity.internalServerError().body(body);
        }

        List<List<String>> variantChunks = vepService.getVariantChunks(variantList, 1);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, format));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @GetMapping("/vep/human/region/{*variant}")
    public ResponseEntity<Object> annotateRegion(@PathVariable String variant) {
        List<List<String>> variantChunks = new ArrayList<>();       
        variantChunks.add(Arrays.asList(variant.substring(1)));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, Optional.of("region")));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @PostMapping("/vep/human/region")
    public ResponseEntity<Object> annotateRegion(@RequestBody List<String> variants) {
        List<List<String>> variantChunks = vepService.getVariantChunksByChromosome(variants);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, Optional.of("region")));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @GetMapping("/info/software")
    public ResponseEntity<Object> getVEPSoftwareVersion() {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("server", serverVersion);
            response.put("release", vepService.getVEPVersion());
            return ResponseEntity.ok().body(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    private Map<String, String> constructErrorMessage(Exception e) {
        return Map.of("error", e.getMessage());
    }

    // As of 04/01/26, hgvs variants of types 'inv' and 'dup' will never be passed to VEP
    public static String hgvsgToEnsembl(String variant) throws IllegalArgumentException {
        Pattern hgvsPattern = Pattern.compile("^(.+):g\\.(\\d+)(?:_(\\d+))?([A-Z]>|ins|delins|del)?([A-Z]*)$");
        
        Matcher matcher = hgvsPattern.matcher(variant);
        if (matcher.find()) {
            String chromosome = matcher.group(1);
            String start = matcher.group(2);
            String end = (matcher.group(3) != null) ? matcher.group(3) : start;
            String ref = "N";
            String variantType = matcher.group(4);
            if (variantType.equals("ins")) {
                int startInt;
                int endInt;
                try {
                    startInt = Integer.parseInt(start);
                    endInt = Integer.parseInt(end);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid HGVSg format: start and end must both be integers: " + variant);
                }

                if (startInt + 1 != endInt) {
                    throw new IllegalArgumentException("Invalid HGVSg format: start + 1 must equal end for insertions: " + variant);
                }

                String t = start;
                start = end;
                end = t;
                ref = "-";
            }
            String altMatch = matcher.group(5);
            String alt = (altMatch == null || altMatch.isEmpty()) ? "-" : altMatch;
            return String.format("%s %s %s %s/%s + %s", chromosome, start, end, ref, alt, variant);
        }
        throw new IllegalArgumentException("Invalid HGVSg format: " + variant);
    }
}
