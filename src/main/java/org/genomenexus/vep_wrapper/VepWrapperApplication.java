package org.genomenexus.vep_wrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VEPConfiguration.class)
public class VepWrapperApplication {

	public static void main(String[] args) {
		SpringApplication.run(VepWrapperApplication.class, args);
	}

}
