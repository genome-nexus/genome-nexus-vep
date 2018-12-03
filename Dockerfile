FROM ensemblorg/ensembl-vep:release_92.1
USER root
RUN apt-get update && apt-get -y install openjdk-8-jre
USER vep
COPY target/vep_wrapper*.jar /opt/vep/src/ensembl-vep/vep_wrapper.jar
CMD ["java", "-jar", "vep_wrapper.jar"]