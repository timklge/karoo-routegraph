package de.timklge.karooroutegraph

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


fun<T> Flow<T>.throttle(timeout: Long): Flow<T> = flow {
    var lastEmissionTime = 0L

    collect { value ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmissionTime >= timeout) {
            emit(value)
            lastEmissionTime = currentTime
        }
    }
}

@Serializable
data class HeightResponse(
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("range_height") val rangeHeight: List<List<Double>>,
    val warnings: List<String>? = emptyList(),
)

@Serializable
data class HeightRequest(
    val range: Boolean,
    @SerialName("shape_format") val shapeFormat: String,
    @SerialName("encoded_polyline") val encodedPolyline: String,
    @SerialName("resample_distance") val resampleDistance: Double? = null,
    @SerialName("height_precision") val heightPrecision: Int = 0,
)

