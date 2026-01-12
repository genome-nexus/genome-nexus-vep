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

    # Ensure the plugins directory exists
    docker exec $CONTAINER_NAME mkdir -p $PLUGIN_DIR

    # Copy custom plugin to the VEP plugin directory
    docker cp $PWD/plugin-data/PolyPhen_SIFT.pm $CONTAINER_NAME:$PLUGIN_DIR/PolyPhen_SIFT.pm
    echo "Custom plugin copied to $PLUGIN_DIR directory."

    # Fix MySQL 8.0 compatibility issue with 'rank' keyword
    # In MySQL 8.0+, 'rank' is a reserved keyword and must be quoted
    echo "Patching VEP for MySQL 8.0 compatibility..."

    # Patch all Perl modules to quote 'rank' column (MySQL 8.0 reserved keyword)
    docker exec $CONTAINER_NAME sh -c '
        echo "Scanning for files with SQL rank keyword usage..."
        PATCHED_COUNT=0

        # Find all .pm files in VEP installation
        find /opt/vep -type f -name "*.pm" 2>/dev/null | while read file; do
            # Check if file contains SQL with unquoted rank
            if grep -q "SELECT.*rank" "$file" 2>/dev/null; then
                # Create backup
                cp "$file" "${file}.bak" 2>/dev/null || true

                # Patch various patterns:
                # Pattern 1: "SELECT ... rank, ..." or "SELECT ... rank "
                sed -i "s/SELECT \(.*\)rank, \(.*\)/SELECT \1\`rank\`, \2/g" "$file" 2>/dev/null || true
                sed -i "s/SELECT \(.*\)rank \"/SELECT \1\`rank\` \"/g" "$file" 2>/dev/null || true

                # Pattern 2: "... rank, ..." in middle of SELECT
                sed -i "s/, rank, /, \`rank\`, /g" "$file" 2>/dev/null || true
                sed -i "s/, rank /, \`rank\` /g" "$file" 2>/dev/null || true

                echo "Patched: $file"
                PATCHED_COUNT=$((PATCHED_COUNT + 1))
            fi
        done

        echo "MySQL 8.0 compatibility: Patched $PATCHED_COUNT files"
    '

    echo "MySQL 8.0 compatibility patches applied."
else
    echo "VEP version $VERSION_NUM detected (>= 109). Plugins are included in the image."
    echo "Custom plugin is available at /plugin-data/PolyPhen_SIFT.pm if needed."
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