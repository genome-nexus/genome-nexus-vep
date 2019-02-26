# genome-nexus-vep
Spring boot minimal REST wrapper service for
[VEP](https://github.com/Ensembl/ensembl-vep). This allows us to deploy a web
service containing VEP without any other components of Ensembl REST API. The
Ensembl VEP docker image has been extended to include the java runtime
environment and this jar.

## Compile and run

```bash
# Point to VEP cache directory. If you don't have this cache, see section "Create VEP cache"
VEP_CACHE=<local_vep_cache_directory_path>

# Set VEP assembly, for example GRCh37 or GRCh38
VEP_ASSEMBLY=GRCh38

# Create docker image
docker build -t genome-nexus_vep .

# Run Genome-Nexus-VEP on port 8889 and point it to local cache
docker run -d --name genome-nexus_vep -p 8889:8080 -e VEP_ASSEMBLY=$VEP_ASSEMBLY -v $VEP_CACHE:/opt/vep/.vep/:ro genome-nexus_vep:latest
```

Genome Nexus VEP is now running at http://localhost:8889

## Create VEP cache
If you don't have a prepared VEP cache directory, start the container with the folder mounted with ":rw" (read&write) and install the VEP cache using INSTALL.pl. Downloading and optimizing the cache will take several hours.

```bash
# Point this to your preferred directory
VEP_CACHE=<local_vep_cache_directory_path>

# Ideally this is added to ~/.bashrc or ~/.bash_profile:
# export VEP_CACHE=<local_vep_cache_directory_path>
# And reload this file with:
# 'source ~/.bashrc' or 'source ~/.bash_profile'

# Create docker image
docker build -t genome-nexus_vep .

# Run Genome-Nexus-VEP on port 8889 and mount cache directory with write access.
docker run -d --name genome-nexus_vep_cache -p 8890:8080 -v $VEP_CACHE:/opt/vep/.vep/:rw genome-nexus_vep:latest

# Go into the docker container
docker exec -it genome-nexus_vep_cache bash

# Run the VEP installer and follow the instructions
# Suggested installation settings can be found below this code block
./INSTALL.pl

# When done, exit and remove the container
exit
docker stop genome-nexus_vep_cache; docker rm genome-nexus_vep_cache;

```
Installation instructions:
- Do you want to continue installing the API (y/n)? -> n
- Do you want to install any cache files (y/n)? -> y, install GRCh37 or GRCh38 cache
- Do you want to install any FASTA files (y/n)? -> y, install GRCh37 or GRCh38 FASTA
- Do you want to install any plugins (y/n)? -> n
