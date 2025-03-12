# Genome Nexus VEP

Genome Nexus VEP is a small REST wrapper around the [Ensembl Variant Effect Predictor (VEP)]((https://useast.ensembl.org/info/docs/tools/vep/index.html)) command line interface. It exposes the following endpoints to interface with VEP:

```
GET /vep/human/hgvs/{variant}
POST /vep/human/hgvs
```

Each endpoint expects variant(s) to be in [HGVS format](https://hgvs-nomenclature.org/stable/background/simple/). See the implementation [here](/src/main/java/org/genomenexus/vep_wrapper/HGVSController.java).

# Software Requirements

Make sure you fave the following installed

- **Java version: 21**
- **Maven version: >= 3.6.3**
- **Docker**

# Download the Ensembl Data

## Download the core database (Required)

1. Download the core database for the ensembl data version you wish to install. The URL containing the data files should be of the format `https://ftp.ensembl.org/pub/release-112/mysql/homo_sapiens_core_XXX_<ASSEMBLY_VERSION>/`.
2. Follow the [installation instructions](https://useast.ensembl.org/info/docs/webcode/mirror/install/ensembl-data.html#:~:text=To%20install%20the%20Ensembl%20Data,separate%20directories%20for%20each%20database.) to set up your database.
3. Point the VEP at your database in your application properties.

## Supporting Polyphen & Sift Predictions (Optional)

1. Download the SQLite database corresponding to the data version pointed to by your application properties. The URL containing the database should be of the format `https://ftp.ensembl.org/pub/release-XXX/`. 
2. Download the PolyPhen_SIFT Perl Module corresponding to the data version pointed to by your application properties. The URL containing the file should be of the format `https://github.com/Ensembl/VEP_plugins/blob/release/XXX/PolyPhen_SIFT.pm`.
3. Place both your installed database and the PolyPhen_SIFT Perl Module in the [plugin-data](/plugin-data) directory.
4. Set the `polyphen-sift-filename` property in your application properties to the name of the installed database file.

## Supporting AlphaMissense Pathogenicity Scores (Optional)

1. Download the [prediction score file](https://console.cloud.google.com/storage/browser/dm_alphamissense) corresponding to your assembly version (`AlphaMissense_hg19.tsv.gz` for GRCh37 or `AlphaMissense_hg38.tsv.gz` for GRCh38).
2. Place the file in the [plugin-data](/plugin-data) directory.
3. Run `tabix -s 1 -b 2 -e 2 -f <PREDICTION_SCORE_FILE>`.
4. Set the `alpha-missense-filename` property in your application properties to the name of the installed file (not the generated tabix file).

# Development

1. Run `./scripts/init_vep.sh <ensemblorg/ensembl-vep:tag>` to install and run a VEP docker image, specifying the tag you wish to use. This will also generate a script to be used by the application, `./scripts/vep`, which should not be modified.
2. Set your VEP configuration in [application-dev.yaml](/src/main/resources/application-dev.yaml).
3. Run `mvn spring-boot:run`

# Building for Production

1. Make sure the VEP version is correct in the [Dockerfile](/Dockerfile).
2. Set your VEP configuration in [application-prod.yaml](/src/main/resources/application-dev.yaml).\
**IMPORTANT**: Make sure `vep.version` is the same as the version used in the [Dockerfile](/Dockerfile). This version will be attached to all responses from the server.
3. Run `docker build <DOCKER_ARGS> .` to build the production image.