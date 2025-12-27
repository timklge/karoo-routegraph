#!/bin/bash

JSON_FILE="countries.json"
INPUT_PBF="pois.osm.pbf"
PLANET_PBF="planet-latest.osm.pbf"
POI_FILTER_FILE="poi-filter.txt"
OUT_DIR="output"
export OSMIUM_POOL_THREADS=4

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo "Error: jq is not installed. Please install it to parse JSON."
    exit 1
fi

if [[ ! -f "$PLANET_PBF" ]]; then
    echo "Error: $PLANET_PBF not found. Please download the planet file."
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p "$OUT_DIR/pois"

#pyosmium-up-to-date "$PLANET_PBF"
#osmium tags-filter "$PLANET_PBF" --expressions="$POI_FILTER_FILE" -o "$INPUT_PBF" --overwrite

# Verify files exist
if [[ ! -f "$JSON_FILE" ]]; then
    echo "Error: $JSON_FILE not found."
    exit 1
fi

if [[ ! -f "$INPUT_PBF" ]]; then
    echo "Error: $INPUT_PBF not found. Please ensure your source PBF file is present."
    exit 1
fi

# Iterate through the JSON entries
jq -r 'to_entries[] | "\(.key) \(.value[1] | join(","))"' "$JSON_FILE" | while read -r code bbox; do
    # Convert country code to lowercase (e.g., US -> us)
    low_code=$(echo "$code" | tr '[:upper:]' '[:lower:]')

    output_file="pois.${low_code}.osm.pbf"

    echo "Processing $code..."

    osmium extract --bbox "$bbox" "$INPUT_PBF" -o tmp.osm.pbf --overwrite
    ./sqlite/osm-to-sqlite tmp.osm.pbf "$OUT_DIR/pois/pois.${low_code}.db" 
done

#mv output/* /srv/routegraph/