POI extraction
=======================

This folder contains a script and go program to extract POIs from an OSM world dump per country. The script will run `pyosmium-up-to-date` to update the planet dump
to the current state. For each country, a sqlite db is created containing all nodes
and tags matching the criteria in `poi-filter.txt`.

To run:
```sh
docker build -t osm-poi-extract ./osm
podman run -v ~/osm/planet-latest.osm.pbf:/app/planet-latest.osm.pbf -v ./output:/app/output osm-poi-extract:latest 
```