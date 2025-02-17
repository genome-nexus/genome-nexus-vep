#!/bin/bash
set -e

if [ $# -lt 1 ]; then
    echo "Usage: $0 <tag>"
    exit 1
fi

TAG=$1
IMAGE_NAME="ensemblorg/ensembl-vep:$TAG"
CONTAINER_NAME="vep-$TAG"

# Start container in interactive mode with a persistent shell
docker run -dt \
    --name $CONTAINER_NAME \
    --mount type=bind,src=$PWD/plugin-data,dst=/plugin-data \
    $IMAGE_NAME \
    /bin/bash

docker cp $PWD/plugin-data/PolyPhen_SIFT.pm $CONTAINER_NAME:plugins/PolyPhen_SIFT.pm

# Create command passthrough script
cat > ./scripts/vep << EOF
#!/bin/bash

# This script is generated automatically by init_vep.sh - do not modify

docker exec ${CONTAINER_NAME} vep "\$@"
EOF

chmod +x ./scripts/vep

echo 
echo "VEP running in container '${CONTAINER_NAME}' and is accessible from the scripts directory."
echo "Use ./scripts/vep --help to get started" 