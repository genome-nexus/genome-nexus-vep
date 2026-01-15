FROM maven:3.9.5 AS builder
RUN mkdir /genome-nexus-vep
ADD . /genome-nexus-vep
WORKDIR /genome-nexus-vep
RUN mvn -DskipTests -Pprod clean install

# ensembl-vep image could have different versions of vep than their tag name, like release_98.0 could have vep 97
# need to run .vep to check the version first before making the final image
ARG VEP_VERSION=release_98.3
FROM ensemblorg/ensembl-vep:${VEP_VERSION}
USER root
RUN apt-get update && apt-get -y install openjdk-21-jre-headless
WORKDIR /
COPY --from=builder /genome-nexus-vep/plugin-data/PolyPhen_SIFT.pm /plugins
COPY --from=builder /genome-nexus-vep/plugin-data /plugin-data
COPY --from=builder /genome-nexus-vep/target/vep_wrapper*.war /vep_wrapper.war
RUN ln -s /opt/vep/src/ensembl-vep /scripts

ENV JAVA_MAX_HEAP=6g \
    JAVA_INITIAL_HEAP=512m \
    JAVA_MAX_RAM_PERCENTAGE=75.0

USER vep
ENTRYPOINT java -Xmx${JAVA_MAX_HEAP} -Xms${JAVA_INITIAL_HEAP} -XX:+UseContainerSupport -XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE} -jar vep_wrapper.war --spring.profiles.active=prod
