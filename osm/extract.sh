#!/bin/bash

JSON_FILE="countries.json"
POI_FILTER_FILE="poi-filter.txt"
OUT_DIR="output"
export OSMIUM_POOL_THREADS=16

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo "Error: jq is not installed. Please install it to parse JSON."
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p "$OUT_DIR/pois"

# Verify files exist
if [[ ! -f "$JSON_FILE" ]]; then
    echo "Error: $JSON_FILE not found."
    exit 1
fi

# Check if country PBF files are mounted (e.g., finland-latest.osm.pbf)
COUNTRY_PBF_FILES=$(find /app -name "*.osm.pbf" -not -name "pois.*.osm.pbf" 2>/dev/null)

if [[ -z "$COUNTRY_PBF_FILES" ]]; then
    echo "Error: No .osm.pbf country files found. Please mount them to /app."
    exit 1
fi

# Filter each country file for POIs and convert to SQLite
for COUNTRY_FILE in $COUNTRY_PBF_FILES; do
    # Extract country code from filename (e.g., finland-latest.osm.pbf -> FI)
    FILENAME=$(basename "$COUNTRY_FILE" .osm.pbf)
    COUNTRY_NAME=$(echo "$FILENAME" | sed 's/-latest//;s/-.*//' | awk '{print toupper(substr($0,1,1)) tolower(substr($0,2))}')

    # Look up the country code from countries.json (case-insensitive match)
    COUNTRY_CODE=$(jq -r --arg name "$COUNTRY_NAME" 'to_entries[] | select(.value[0] | ascii_downcase == ($name | ascii_downcase)) | .key' "$JSON_FILE")

    if [[ -z "$COUNTRY_CODE" ]]; then
        echo "Warning: Could not find country code for $COUNTRY_NAME, skipping."
        continue
    fi

    # Convert country code to lowercase (e.g., US -> us)
    low_code=$(echo "$COUNTRY_CODE" | tr '[:upper:]' '[:lower:]')

    echo "Processing $COUNTRY_CODE ($COUNTRY_NAME)..."

    # Filter POIs and convert directly to SQLite
    osmium tags-filter "$COUNTRY_FILE" --expressions="$POI_FILTER_FILE" -o tmp.osm.pbf --overwrite
    ./sqlite/osm-to-sqlite tmp.osm.pbf "$OUT_DIR/pois/pois.${low_code}.db"
    gzip -9k "$OUT_DIR/pois/pois.${low_code}.db"
    rm -f tmp.osm.pbf
done

echo "Done! Output files are in /app/$OUT_DIR/pois/"