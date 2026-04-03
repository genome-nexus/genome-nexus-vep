# ensembl-vep image could have different versions of vep than their tag name, like release_98.0 could have vep 97
# need to run .vep to check the version first before making the final image
ARG VEP_VERSION=release_98.3

FROM maven:3.9.5 AS builder
RUN mkdir /genome-nexus-vep
ADD . /genome-nexus-vep
WORKDIR /genome-nexus-vep
RUN mvn -DskipTests -Pprod clean install

FROM ensemblorg/ensembl-vep:${VEP_VERSION}
USER root
RUN apt-get update && apt-get -y install wget apt-transport-https gnupg libdbd-sqlite3-perl && \
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | apt-key add - && \
    echo "deb https://packages.adoptium.net/artifactory/deb bionic main" | tee /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get -y install temurin-21-jre
WORKDIR /
COPY --from=builder /genome-nexus-vep/plugin-data/PolyPhen_SIFT.pm /plugins/
COPY --from=builder /genome-nexus-vep/plugin-data/AlphaMissense.pm /plugins/
COPY --from=builder /genome-nexus-vep/plugin-data /plugin-data
COPY --from=builder /genome-nexus-vep/target/vep_wrapper*.war /vep_wrapper.war
RUN ln -s /opt/vep/src/ensembl-vep /scripts && \
    find /opt/vep/src/ensembl-vep -type f -name "*.pm" -exec sed -i \
        -e 's/, rank,/, `rank`,/g' \
        -e 's/, rank /, `rank` /g' \
        -e 's/,rank,/,`rank`,/g' \
        -e 's/,rank)/,`rank`)/g' \
        -e 's/, version,/, `version`,/g' \
        -e 's/, version /, `version` /g' \
        -e 's/ version FROM/ `version` FROM/g' \
        -e 's/SELECT version /SELECT `version` /g' \
    {} +

ENV JAVA_MAX_HEAP=6g \
    JAVA_INITIAL_HEAP=512m \
    JAVA_MAX_RAM_PERCENTAGE=75.0

USER vep
ENTRYPOINT java -Xmx${JAVA_MAX_HEAP} -Xms${JAVA_INITIAL_HEAP} -XX:+UseContainerSupport -XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE} -jar vep_wrapper.war --spring.profiles.active=prod
