# genome-nexus-vep
Spring boot minimal REST wrapper service for
[VEP](https://github.com/Ensembl/ensembl-vep). This allows us to deploy a web
service containing VEP without any other components of Ensembl REST API. The
Ensembl VEP docker image has been extended to include the java runtime
environment and this jar.

## Compile and run
```
mvn  -DskipTests clean install && docker build -t genome-nexus-vep . && docker  run -p 8080:8080 -it -e "TERM=xterm-256color" genome-nexus-vep:latest
```