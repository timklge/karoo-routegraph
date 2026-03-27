package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"github.com/qedus/osmpbf"
)

const (
	batchSize = 2_000000
)

type LatLng struct {
	Lat float64
	Lon float64
}

var nodes = make(map[int64]LatLng)
var ways = make(map[int64]*osmpbf.Way)

func main() {
	if len(os.Args) < 3 {
		fmt.Printf("Usage: %s <inputFile> <dbFile>\n", os.Args[0])
		os.Exit(1)
	}
	inputFile := os.Args[1]
	dbFile := os.Args[2]

	// Open the PBF file
	f, err := os.Open(inputFile)
	if err != nil {
		log.Fatalf("Error opening input file: %v", err)
	}
	defer f.Close()

	// Initialize PBF decoder
	d := osmpbf.NewDecoder(f)
	d.SetBufferSize(osmpbf.MaxBlobSize)
	err = d.Start(runtime.GOMAXPROCS(-1))
	if err != nil {
		log.Fatalf("Error starting decoder: %v", err)
	}

	// Remove existing DB file to start fresh
	if _, err := os.Stat(dbFile); err == nil {
		os.Remove(dbFile)
	}

	// Open SQLite database
	db, err := sql.Open("sqlite3", dbFile)
	if err != nil {
		log.Fatalf("Error opening database: %v", err)
	}
	defer db.Close()

	// Create tables
	sqlStmt := `
	CREATE TABLE nodes (
		id INTEGER PRIMARY KEY,
		lat REAL,
		lon REAL,
		tags TEXT,
		radius REAL
	);
	`
	_, err = db.Exec(sqlStmt)
	if err != nil {
		log.Fatalf("Error creating tables: %v", err)
	}

	// Prepare transaction and statements
	tx, err := db.Begin()
	if err != nil {
		log.Fatalf("Error beginning transaction: %v", err)
	}
	stmtNode, err := tx.Prepare("INSERT INTO nodes(id, lat, lon, tags, radius) VALUES(?, ?, ?, ?, ?)")
	if err != nil {
		log.Fatalf("Error preparing node statement: %v", err)
	}

	nodeCount := 0
	wayCount := 0
	startTime := time.Now()

	// Read nodes
	for {
		if v, err := d.Decode(); err == io.EOF {
			break
		} else if err != nil {
			log.Fatalf("Error decoding: %v", err)
		} else {
			switch v := v.(type) {
			case *osmpbf.Node:
				jsonTags, err := json.Marshal(v.Tags)
				if err != nil {
					log.Fatalf("Error marshaling tags for node %d: %v", v.ID, err)
				}

				_, err = stmtNode.Exec(v.ID, v.Lat, v.Lon, jsonTags, 0.0)
				if err != nil {
					log.Fatalf("Error inserting node %d: %v", v.ID, err)
				}

				nodes[v.ID] = LatLng{Lat: v.Lat, Lon: v.Lon}

				nodeCount++
			case *osmpbf.Way:
				ways[v.ID] = v
			case *osmpbf.Relation:
				// Ignore relations
			}
		}
	}

	for _, way := range ways {
		centerLat := 0.0
		centerLon := 0.0
		nodeCount := 0
		radius := 0.0

		for _, nodeID := range way.NodeIDs {
			if latlng, exists := nodes[nodeID]; exists {
				centerLat += latlng.Lat
				centerLon += latlng.Lon
				nodeCount++
			}
		}

		for _, nodeID := range way.NodeIDs {
			if latlng, exists := nodes[nodeID]; exists {
				dx := latlng.Lon - centerLon/float64(nodeCount)
				dy := latlng.Lat - centerLat/float64(nodeCount)
				dist := (dx*dx + dy*dy)
				if dist > radius {
					radius = dist
				}
			}
		}

		if nodeCount > 0 {
			centerLat /= float64(nodeCount)
			centerLon /= float64(nodeCount)
		}

		jsonTags, err := json.Marshal(way.Tags)
		if err != nil {
			log.Fatalf("Error marshaling tags for way %d: %v", -way.ID, err)
		}

		_, err = stmtNode.Exec(-way.ID, centerLat, centerLon, jsonTags, radius)
		if err != nil {
			log.Fatalf("Error inserting way %d: %v", -way.ID, err)
		}

		wayCount++
	}

	// Final commit
	stmtNode.Close()
	err = tx.Commit()
	if err != nil {
		log.Fatalf("Error committing final transaction: %v", err)
	}

	_, err = db.Exec("CREATE INDEX idx_latlon ON nodes(lat, lon)")
	if err != nil {
		log.Fatalf("Error creating lat index: %v", err)
	}

	elapsed := time.Since(startTime)
	fmt.Printf("Finished processing %d nodes and %d ways in %s\n", nodeCount, wayCount, elapsed)
}
