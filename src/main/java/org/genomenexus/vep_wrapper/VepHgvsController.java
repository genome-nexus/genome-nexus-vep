package org.genomenexus.vep_wrapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

@RestController
@CrossOrigin(origins="*")
@RequestMapping(value= "/vep/human/hgvsc")
@ConditionalOnProperty(
    value = "database.enabled",
    havingValue = "true"
)
@Api(tags = "vep-hgvs-controller", description = "VEP HGVS Controller")
public class VepHgvsController {

    @Autowired
    private VepRunner vepRunner;

    @RequestMapping(value = "/{hgvsc}",
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

    @RequestMapping(value = "/",
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
