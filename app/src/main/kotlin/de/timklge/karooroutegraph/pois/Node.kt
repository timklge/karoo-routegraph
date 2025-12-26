package de.timklge.karooroutegraph.pois

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "nodes", indices = [Index(value = ["latitude", "longitude"])])
data class Node(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "latitude") val lat: Double,
    @ColumnInfo(name = "longitude") val lon: Double
)