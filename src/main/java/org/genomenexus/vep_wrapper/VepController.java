package org.genomenexus.vep_wrapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.springframework.web.bind.annotation.*;

@RestController 
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "vep-controller", description = "VEP Controller")
public class VepController {

    @RequestMapping(value = "/vep/human/region/{region}/{allele}",
        method = RequestMethod.GET,
        produces = "application/json")
    @ApiOperation(value = "Retrieves VEP results for single variant specified in region syntax e.g. 17:41242962-41242965:1/GA (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepAnnotationByRegionGET")
    public String getVepAnnotation(@ApiParam(value="region", required=true) @PathVariable String region,
                                   @ApiParam(value="allele", required=true) @PathVariable String allele)
    {
        try {
            return VepRunner.run(Arrays.asList(region + "/" + allele), false);
        } catch (IOException | InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return "Error";
        }
    }

    @RequestMapping(value = "/vep/human/region",
        method = RequestMethod.POST,
        produces = "application/json")
    @ApiOperation(value = "Retrieves VEP annotations for multiple variants specified in region syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepAnnotationByRegionsPOST")
    public String fetchVepAnnotationByRegionsPOST(
        @ApiParam(value = "List of variants in ensembl region format. For example [\"17:41242962-41242965:1/GA\"]",
        required = true)
        @RequestBody List<String> regions)
    {
        try {
            return VepRunner.run(regions, true);
        } catch (IOException | InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            return "Error";
        }
    }
}
