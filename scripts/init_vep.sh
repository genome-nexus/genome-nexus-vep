#!/bin/bash
set -e

if [ $# -lt 1 ]; then
    echo "Usage: $0 <tag>"
    exit 1
fi

TAG=$1
IMAGE_NAME="ensemblorg/ensembl-vep:$TAG"
CONTAINER_NAME="vep-$TAG"

# Extract version number from tag (e.g., release_111.0 -> 111)
VERSION_NUM=$(echo $TAG | grep -o '[0-9]\+' | head -1)

# Start container in interactive mode with a persistent shell
docker run -dt \
    --name $CONTAINER_NAME \
    --mount type=bind,src=$PWD/plugin-data,dst=/plugin-data \
    $IMAGE_NAME \
    /bin/bash

# VEP 109+ includes all plugins in the image, so we don't need to copy custom plugins
# For older versions, we need to copy the custom plugin to the plugins directory
if [ -n "$VERSION_NUM" ] && [ "$VERSION_NUM" -lt 109 ]; then
    echo "VEP version $VERSION_NUM detected (< 109). Applying compatibility patches..."

    # Older versions (like 98) use /opt/vep/plugins
    PLUGIN_DIR="/opt/vep/plugins"

    docker exec $CONTAINER_NAME mkdir -p $PLUGIN_DIR
    docker cp $PWD/plugin-data/PolyPhen_SIFT.pm $CONTAINER_NAME:$PLUGIN_DIR/PolyPhen_SIFT.pm
    docker cp $PWD/plugin-data/AlphaMissense.pm $CONTAINER_NAME:$PLUGIN_DIR/AlphaMissense.pm

    echo "Custom plugin copied to $PLUGIN_DIR directory."

    # Install dependencies for PolyPhen_SIFT plugin (DBD::SQLite)
    echo "Installing dependencies for plugins..."
    docker exec -u 0 $CONTAINER_NAME apt-get update
    docker exec -u 0 $CONTAINER_NAME apt-get install -y libdbd-sqlite3-perl

else
    echo "VEP version $VERSION_NUM detected (>= 109). Plugins are included in the image."
fi

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