package org.genomenexus.vep_wrapper;

import java.io.IOException;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

@RestController 
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "vep-controller", description = "VEP Controller")
public class VepController {

    @RequestMapping(value = "/vep",
    method = RequestMethod.GET,
    produces = "application/json")
    @ApiOperation(value = "Retrieves VEP results for single variant",
    nickname = "getVepAnnotationGET")
    public String greeting(@ApiParam(value="name", required=true) String name) {
        try {
			return VepRunner.run(name);
		} catch (IOException | InterruptedException e) {
			// TODO Auto-generated catch block
            e.printStackTrace();
            return "Error";
		}
    }
}