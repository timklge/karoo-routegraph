package de.timklge.karooroutegraph.incidents

import kotlinx.serialization.Serializable

@Serializable
data class IncidentGeospatialParameters(
    val type: String? = null,
    val corridor: String? = null,
    val center: IncidentPoint? = null,
    val radius: Int? = null,
)

@Serializable
data class IncidentRequestParameters(
    val `in`: IncidentGeospatialParameters? = null,
    val locationReferencing: List<String>? = null,
)

@Serializable
data class IncidentsResponse(
    val sourceUpdated: String? = null,
    val results: List<IncidentResult>? = null
)

@Serializable
data class IncidentResult(
    val location: IncidentLocation? = null,
    val incidentDetails: IncidentDetails? = null
)

@Serializable
data class IncidentLocation(
    val length: Double? = null,
    val shape: IncidentShape? = null
)

@Serializable
data class IncidentShape(
    val links: List<IncidentLink>? = null
)

@Serializable
data class IncidentLink(
    val points: List<IncidentPoint>? = null,
    val length: Double? = null
)

@Serializable
data class IncidentPoint(
    val lat: Double? = null,
    val lng: Double? = null
)

@Serializable
data class IncidentDetails(
    val id: String? = null,
    val hrn: String? = null,
    val originalId: String? = null,
    val originalHrn: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val entryTime: String? = null,
    val roadClosed: Boolean? = null,
    val criticality: String? = null,
    val type: String? = null,
    val codes: List<Int>? = null,
    val description: IncidentText? = null,
    val summary: IncidentText? = null,
    val comment: String? = null,
    val junctionTraversability: String? = null
)

@Serializable
data class IncidentText(
    val value: String? = null,
    val language: String? = null
)

