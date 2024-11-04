package org.genomenexus.vep_wrapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "vep-controller", description = "VEP Controller")
public class VepController {

    @Autowired
    private VepRunner vepRunner;

    @RequestMapping(value = "/vep/human/region/{region}/{allele}",
        method = RequestMethod.GET,
        produces = "application/json")
    @ApiOperation(value = "Retrieves VEP results for single variant specified in region syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepAnnotationByRegionGET")
    public void getVepAnnotation(
            @ApiParam(value="GRCh37: 17:41242962-41242965," +
                    "GRCh38: 1:182712-182712", required=true)
            @PathVariable
            String region,
            @ApiParam(value="GRCh37: GA, " +
                    "GRCh38: C", required=true)
            @PathVariable
            String allele,
            @ApiParam("Maximum time (in seconds) to let VEP construct a response (0 = no limit)")
            @RequestParam(defaultValue = "0")
            Integer responseTimeout,
            HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            vepRunner.run(Arrays.asList(region + "/" + allele), false, responseTimeout, out, false);
        } catch (IOException | InterruptedException | VepLaunchFailureException e) {
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
            @RequestBody
            List<String> regions,
            @ApiParam("Maximum time (in seconds) to let VEP construct a response (0 = no limit)")
            @RequestParam(defaultValue = "0")
            Integer responseTimeout,
            HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            vepRunner.run(regions, true, responseTimeout, out, false);
        } catch (IOException | InterruptedException | VepLaunchFailureException e) {
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

    @RequestMapping(value = "/vep/human/hgvsc/{hgvsc}",
        method = RequestMethod.GET,
        produces = "application/json")
    @ApiOperation(value = "Retrieves VEP results for single c. variant specified in hgvs syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepHgvscAnnotationByGET")
    public void getVepHgvscAnnotation(
            @ApiParam(value="ENST00000618231.3:c.9G>C", required=true)
            @PathVariable
            String hgvsc,
            @ApiParam("Maximum time (in seconds) to let VEP construct a response (0 = no limit)")
            @RequestParam(defaultValue = "0")
            Integer responseTimeout,
            HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            vepRunner.run(Arrays.asList(hgvsc), false, responseTimeout, out, true);
        } catch (IOException | InterruptedException | VepLaunchFailureException e) {
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

    @RequestMapping(value = "/vep/human/hgvsc",
        method = RequestMethod.POST)
    @ApiOperation(value = "Retrieves VEP results for multiple c. variants specified in hgvs syntax (https://ensembl.org/info/docs/tools/vep/vep_formats.html)",
        nickname = "fetchVepHgvscAnnotationsByPOST")
    public void fetchVepHgvscAnnotationsPOST(
            @ApiParam(value = "List of variants in ENSEMBL hgvsc format. For example:\n" +
                    "[\"ENST00000618231.3:c.9G>C\", \"ENST00000471631.1:c.28_33delTCGCGG\"]",
                    required = true)
            @RequestBody
            List<String> hgvscStrings,
            @ApiParam("Maximum time (in seconds) to let VEP construct a response (0 = no limit)")
            @RequestParam(defaultValue = "0")
            Integer responseTimeout,
            HttpServletResponse response) {
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            response.setContentType("application/json");
            vepRunner.run(hgvscStrings, true, responseTimeout, out, true);
        } catch (IOException | InterruptedException | VepLaunchFailureException e) {
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
