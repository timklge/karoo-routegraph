package de.timklge.karooroutegraph.pois

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_pbf")
data class DownloadedPbf(
    @PrimaryKey val countryKey: String,
    @ColumnInfo(name = "country_name") val countryName: String,
    @ColumnInfo(name = "pbf_type") val pbfType: PbfType,
    @ColumnInfo(name = "download_status") val downloadState: PbfDownloadStatus,
    @ColumnInfo(name = "progress") val progress: Float,
)