# Genome Nexus VEP

Genome Nexus VEP is a small REST wrapper around the [Ensembl Variant Effect Predictor (VEP)](<(https://useast.ensembl.org/info/docs/tools/vep/index.html)>) command line interface. It exposes the following endpoints to interface with VEP:

```sh
GET /vep/human/hgvs/{variant}
POST /vep/human/hgvs
```

Each endpoint expects variant(s) to be in [HGVS format](https://hgvs-nomenclature.org/stable/background/simple/). See the implementation [here](/src/main/java/org/genomenexus/vep_wrapper/HGVSController.java).

## Software Requirements

Make sure you fave the following installed

- **Java version: 21**
- **Maven version: >= 3.6.3**
- **Docker**

## Download the Ensembl Data

## Database Mode (Preferred)

Database mode is the preferred way to use Genome Nexus VEP and provides the same functionality as the public Ensembl REST API.

### Download the core database (Required)

#### Option 1: Download from Ensembl (slower)

1. Download the core database for the ensembl data version you wish to install. The URL containing the data files should be of the format `https://ftp.ensembl.org/pub/release-XXX/mysql/homo_sapiens_core_XXX_<ASSEMBLY_VERSION>/`.
2. Follow the [installation instructions](https://useast.ensembl.org/info/docs/webcode/mirror/install/ensembl-data.html#:~:text=To%20install%20the%20Ensembl%20Data,separate%20directories%20for%20each%20database.) to set up your database.
3. Point the VEP at your database in your application properties.

#### Option 2: Download from Genome Nexus S3 Bucket (faster)

1. Download the SQL `homo_sapiens_core_XXX_<ASSEMBLY_VERSION>` SQL files from
   the Genome Nexus S3 Bucket.
2. Make sure you change you `my.cnf` file to support a larger packet size

   ```cnf
   [mysqld]
   # Other configurations....
   max_allowed_packet=1G
   ```

3. If you make a configuration change, then restart the mysql server
4. Add the data to the database

   ```sh
   mysql -u <username> -p homo_sapiens_core_XXX_<ASSEMBLY_VERSION> < homo_sapiens_core_XXX_<ASSEMBLY_VERSION>.sql
   ```

## Cache Mode

Cache mode is intended for users who cannot support the database. However, the functionality of VEP is limited if you choose to use cache mode. You will not be able to annotate variants whose coordinates are non-genomic, and you will not be able to annotate HGVSg inversions and duplications.

### Download the cache files

1. Download the VEP cache file and FASTA file for the ensembl data version you wish to install. Follow Ensembl's [installation instructions](ensebml.org/info/docs/tools/vep/script/vep_cache.html)
2. Place both your VEP cache file and the FASTA in the [plugin-data](/plugin-data) directory
3. Set the `fasta-filename` property in your application properties to the name of the installed FASTA file and set `mode` to cache.

#### Option 2: Download from Genome Nexus S3 Bucket (faster)

1. Download the SQL `homo_sapiens_core_XXX_<ASSEMBLY_VERSION>` SQL files from
   the Genome Nexus S3 Bucket.
2. Make sure you change you `my.cnf` file to support a larger packet size

   ```cnf
   [mysqld]
   # Other configurations....
   max_allowed_packet=1G
   ```

3. If you make a configuration change, then restart the mysql server
4. Add the data to the database

   ```sh
   mysql -u <username> -p homo_sapiens_core_XXX_<ASSEMBLY_VERSION> < homo_sapiens_core_XXX_<ASSEMBLY_VERSION>.sql
   ```

### Supporting Polyphen & Sift Predictions (Optional)

1. Download the SQLite database corresponding to the data version pointed to by your application properties. The URL containing the database should be of the format `https://ftp.ensembl.org/pub/release-XXX/`.
2. Download the PolyPhen_SIFT Perl Module corresponding to the data version pointed to by your application properties. The URL containing the file should be of the format `https://github.com/Ensembl/VEP_plugins/blob/release/XXX/PolyPhen_SIFT.pm`.
3. Place both your installed database and the PolyPhen_SIFT Perl Module in the [plugin-data](/plugin-data) directory.
4. Set the `polyphen-sift-filename` property in your application properties to the name of the installed database file.

### Supporting AlphaMissense Pathogenicity Scores (Optional)

1. Download the [prediction score file](https://console.cloud.google.com/storage/browser/dm_alphamissense) corresponding to your assembly version (`AlphaMissense_hg19.tsv.gz` for GRCh37 or `AlphaMissense_hg38.tsv.gz` for GRCh38).
2. Place the file in the [plugin-data](/plugin-data) directory.
3. Run `tabix -s 1 -b 2 -e 2 -f <PREDICTION_SCORE_FILE>`.
4. Set the `alpha-missense-filename` property in your application properties to the name of the installed file (not the generated tabix file).

## Development

1. Run `./scripts/init_vep.sh <tag for ensemblorg/ensembl-vep image>` to install and run a VEP docker image, specifying the tag you wish to use. This will also generate a script to be used by the application, `./scripts/vep`, which should not be modified.

   - If you want to test the VEP command to see if it's working. Run the following:

       ```sh
       ./scripts/vep \
           --database \
           --host=host.docker.internal \
           --port=3306 \
           --user=<db-username> \
           --password=<db-password> \
           --fork=1 \
           --format=hgvs \
           --input_data="7:g.55249071T>C" \
           --output_file=STDOUT \
           --warning_file=STDERR \
           --everything \
           --hgvsg \
           --no_stats \
           --xref_refseq \
           --json
       ```

2. Set your VEP configuration in [application-dev.yaml](/src/main/resources/application-dev.yaml).
   - Make sure you have `host.docker.internal` set as the VEP host (if running `./script/vep`)
3. Run `mvn spring-boot:run`

## Building for Production

1. Make sure the VEP version is correct in the [Dockerfile](/Dockerfile).
2. Set your VEP configuration in [application-prod.yaml](/src/main/resources/application-dev.yaml).\
   **IMPORTANT**: Make sure `vep.version` is the same as the version used in the [Dockerfile](/Dockerfile). This version will be attached to all responses from the server.
3. Run `docker build <DOCKER_ARGS> .` to build the production image.
