package main

import (
	"database/sql"
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
	os.Remove(dbFile)

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
		tags TEXT
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
	stmtNode, err := tx.Prepare("INSERT INTO nodes(id, lat, lon, tags) VALUES(?, ?, ?, ?)")
	if err != nil {
		log.Fatalf("Error preparing node statement: %v", err)
	}

	count := 0
	startTime := time.Now()

	for {
		if v, err := d.Decode(); err == io.EOF {
			break
		} else if err != nil {
			log.Fatalf("Error decoding: %v", err)
		} else {
			switch v := v.(type) {
			case *osmpbf.Node:
				name := v.Tags["name"]
				_, err = stmtNode.Exec(v.ID, v.Lat, v.Lon, name)
				if err != nil {
					log.Fatalf("Error inserting node %d: %v", v.ID, err)
				}

				count++
				if count%batchSize == 0 {
					stmtNode.Close()
					err = tx.Commit()
					if err != nil {
						log.Fatalf("Error committing transaction: %v", err)
					}

					fmt.Printf("Processed %d nodes...\n", count)

					// Start new transaction
					tx, err = db.Begin()
					if err != nil {
						log.Fatalf("Error beginning transaction: %v", err)
					}
					stmtNode, err = tx.Prepare("INSERT INTO nodes(id, lat, lon, tags) VALUES(?, ?, ?, ?)")
					if err != nil {
						log.Fatalf("Error preparing node statement: %v", err)
					}
				}
			case *osmpbf.Way:
				// Ignore ways
			case *osmpbf.Relation:
				// Ignore relations
			}
		}
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
	fmt.Printf("Finished processing %d nodes in %s\n", count, elapsed)
}
