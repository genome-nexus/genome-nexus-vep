FROM maven:3.9.5 AS builder
RUN mkdir /genome-nexus-vep
ADD . /genome-nexus-vep
WORKDIR /genome-nexus-vep
RUN mvn -DskipTests clean install

FROM ensemblorg/ensembl-vep:release_112.0
USER root
RUN apt-get update && apt-get -y install openjdk-21-jre-headless
WORKDIR /
COPY --from=builder /genome-nexus-vep/plugin-data/PolyPhen_SIFT.pm /plugins
COPY --from=builder /genome-nexus-vep/plugin-data /plugin-data
COPY --from=builder /genome-nexus-vep/target/vep_wrapper*.war /vep_wrapper.war
RUN ln -s /opt/vep/src/ensembl-vep /scripts
USER vep
ENTRYPOINT exec java ${JAVA_OPTS} -jar vep_wrapper.war --spring.profiles.active=prod