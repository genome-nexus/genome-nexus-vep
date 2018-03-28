package org.genomenexus.vep_wrapper;

import com.google.common.base.Predicates;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@SpringBootApplication(scanBasePackages = {"org.genomenexus.vep_wrapper"})
@EnableSwagger2 // enable swagger2 documentation
public class VepWrapper {

	public static void main(String[] args) {
		SpringApplication.run(VepWrapper.class, args);
    }

	private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
            .title("Genome Nexus VEP Wrapper API")
            .description("Genome Nexus Vep Wrapper API")
            //.termsOfServiceUrl("http://terms-of-service-url")
            .contact("CMO, MSKCC")
            .license("GNU AFFERO GENERAL PUBLIC LICENSE Version 3")
            .licenseUrl("https://github.com/genome-nexus/genome-nexus-vep/blob/master/LICENSE")
            .version("2.0")
            .build();
    }

    @Bean
    public Docket swaggerSpringMvcPlugin() {
        return new Docket(DocumentationType.SWAGGER_2)
            .apiInfo(apiInfo())
            .select()
            .paths(Predicates.not(PathSelectors.regex("/error"))) // Exclude Spring error controllers
            .paths(Predicates.not(PathSelectors.regex("/actuator.*"))) // Exclude Actuator controllers
            .paths(Predicates.not(PathSelectors.regex("/"))) // Exclude Root redirect
            .build();
    }
}
