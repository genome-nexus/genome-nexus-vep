package org.genomenexus.vep_wrapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @GetMapping("/vep/human/hgvs/{variant}")
    public ResponseEntity<Object> annotateHGVS(@PathVariable String variant) {
        List<List<String>> variantChunks = new ArrayList<>();
        variantChunks.add(Arrays.asList(variant));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, "hgvs"));
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

        List<List<String>> variantChunks = vepService.getVariantChunks(variantList, 1);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, "hgvs"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @GetMapping("/vep/human/region/{*variant}")
    public ResponseEntity<Object> annotateRegion(@PathVariable String variant) {
        List<List<String>> variantChunks = new ArrayList<>();       
        variantChunks.add(Arrays.asList(variant.substring(1)));
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, "region"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(constructErrorMessage(e));
        }
    }

    @PostMapping("/vep/human/region")
    public ResponseEntity<Object> annotateRegion(@RequestBody List<String> variants) {
        List<List<String>> variantChunks = vepService.getVariantChunksByChromosome(variants);
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(vepService.annotateVariants(variantChunks, "region"));
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
}
