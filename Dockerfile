FROM maven:3.9.5 AS builder
RUN mkdir /genome-nexus-vep
ADD . /genome-nexus-vep
WORKDIR /genome-nexus-vep
RUN mvn -DskipTests -Pprod clean install

# ensembl-vep image could have different versions of vep than their tag name, like release_98.0 could have vep 97
# need to run .vep to check the version first before making the final image
FROM ensemblorg/ensembl-vep:release_98.3
USER root
RUN apt-get update && apt-get -y install openjdk-21-jre-headless
WORKDIR /
COPY --from=builder /genome-nexus-vep/plugin-data/PolyPhen_SIFT.pm /plugins
COPY --from=builder /genome-nexus-vep/plugin-data /plugin-data
COPY --from=builder /genome-nexus-vep/target/vep_wrapper*.war /vep_wrapper.war
RUN ln -s /opt/vep/src/ensembl-vep /scripts
USER vep
ENTRYPOINT ["java", "-jar", "vep_wrapper.war", "--spring.profiles.active=prod"]
