#!/bin/bash
set -e

if [ $# -lt 1 ]; then
    echo "Usage: $0 <tag>"
    exit 1
fi

TAG=$1
IMAGE_NAME="ensemblorg/ensembl-vep:$TAG"
CONTAINER_NAME="vep-$TAG"

if docker container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    read -r -p "Container '$CONTAINER_NAME' already exists. Remove it before continuing? [y/N] " REMOVE_CONTAINER
    if [[ "$REMOVE_CONTAINER" =~ ^[Yy]$ ]]; then
        docker rm -f "$CONTAINER_NAME"
    else
        echo "Aborting because container '$CONTAINER_NAME' already exists."
        exit 1
    fi
fi

if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    read -r -p "Image '$IMAGE_NAME' already exists. Remove it before continuing? [y/N] " REMOVE_IMAGE
    if [[ "$REMOVE_IMAGE" =~ ^[Yy]$ ]]; then
        docker image rm "$IMAGE_NAME"
    fi
fi

# Needed for VEP cache
mkdir -p "$HOME/.vep"

# Start container in interactive mode with a persistent shell
docker run -dt \
    --name "$CONTAINER_NAME" \
    --mount type=bind,src="$PWD/plugin-data",dst=/plugin-data \
    # Needed for VEP cache
    --mount type=bind,src="$HOME/.vep",dst=/opt/vep/.vep \
    "$IMAGE_NAME" \
    /bin/bash

docker cp "$PWD/plugin-data/PolyPhen_SIFT.pm" "$CONTAINER_NAME":plugins/PolyPhen_SIFT.pm

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
