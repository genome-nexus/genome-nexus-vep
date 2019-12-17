package org.genomenexus.vep_wrapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

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
    @ApiOperation(value = "Retrieves VEP results for single variant specified in region syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepAnnotationByRegionGET")
    public void getVepAnnotation(@ApiParam(value="GRCh37: 17:41242962-41242965," +
                                                   "GRCh38: 1:182712-182712", required=true)
                                   @PathVariable
                                   String region,
                                   @ApiParam(value="GRCh37: GA, " +
                                                   "GRCh38: C", required=true)
                                   @PathVariable
                                   String allele,
                                   HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            VepRunner.run(Arrays.asList(region + "/" + allele), false, out);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            // TODO: throw and handle errors with global exception handler
        } finally {
            try {
                response.flushBuffer();
            } catch (Throwable e) {
                e.printStackTrace();
                // TODO: throw and handle errors with global exception handler
            }
        }
    }

    @RequestMapping(value = "/vep/human/region",
        method = RequestMethod.POST)
    @ApiOperation(value = "Retrieves VEP annotations for multiple variants specified in region syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepAnnotationByRegionsPOST")
    public void fetchVepAnnotationByRegionsPOST(
        @ApiParam(value = "List of variants in ENSEMBL region format. For example:\n" +
                "GRCh37: " +
                "[\"17:41242962-41242965:1/GA\"], " +
                "GRCh38: " +
                "[\"1:182712-182712:1/C\", " +
                "\"2:265023-265023:1/T\"," +
                "\"3:319781-319781:1/-\", " +
                "\"19:110748-110747:1/T\", " +
                "\"1:1385015-1387562:1/-\"]",
        required = true)
        @RequestBody List<String> regions, HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            VepRunner.run(regions, true, out);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            // TODO: throw and handle errors with global exception handler
        } finally {
            try {
                response.flushBuffer();
            } catch (Throwable e) {
                e.printStackTrace();
                // TODO: throw and handle errors with global exception handler
            }
        }
        return;
    }

}
