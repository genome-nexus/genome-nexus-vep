FROM maven:3.8.5
RUN mkdir /genome-nexus-vep
ADD . /genome-nexus-vep
WORKDIR /genome-nexus-vep
RUN mvn -DskipTests clean install

FROM ensemblorg/ensembl-vep:release_98.3
USER root
RUN apt-get update && apt-get -y install openjdk-8-jre
COPY --from=0 /genome-nexus-vep/target/vep_wrapper*.jar /opt/vep/src/ensembl-vep/vep_wrapper.jar
USER vep
WORKDIR /opt/vep/src/ensembl-vep/
ENTRYPOINT exec java ${JAVA_OPTS} -jar vep_wrapper.jar
