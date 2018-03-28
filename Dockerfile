FROM ensemblorg/ensembl-vep:release_91.3
USER root
RUN apt-get update && apt-get -y install openjdk-8-jre
USER vep
COPY target/vep_wrapper*.jar /home/vep/src/ensembl-vep/vep_wrapper.jar
CMD ["java", "-jar", "vep_wrapper.jar"]