package de.timklge.karooroutegraph.pois

import kotlinx.serialization.Serializable

@Serializable
data class DownloadedPbf(
    val countryKey: String,
    val countryName: String,
    val pbfType: PbfType,
    val downloadState: PbfDownloadStatus,
    val progress: Float,
)