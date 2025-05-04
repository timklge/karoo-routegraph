package de.timklge.karooroutegraph

enum class OsmMapStyle (val value: String) {
    MAPNIK("mapnik"),
    CYCLEMAP("cyclemap"),
}

data class Tile(val x: Int, val y: Int, val z: Int, val style: OsmMapStyle = OsmMapStyle.MAPNIK)