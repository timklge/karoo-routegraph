package de.timklge.karooroutegraph.pois

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "tags", primaryKeys = ["nodeId", "key"])
data class Tag(
    @ColumnInfo(name = "nodeId") val nodeId: Long,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)