package de.timklge.karooroutegraph.datatypes

import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.TravelTimeEstimationService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

private data class PowerReading(val timestamp: Long, val power: Double)

private data class AveragePowerState(
    val sum: Double,
    val readings: ArrayDeque<PowerReading>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AveragePowerState
        return sum == other.sum && readings == other.readings
    }

    override fun hashCode(): Int {
        var result = sum.hashCode()
        result = 31 * result + readings.hashCode()
        return result
    }
}

internal fun Flow<Pair<Double, Double>>.averagePowerOverHour(totalWeight: Double, currentTimeMillis: () -> Long): Flow<Double> {
    val oneHourInMillis = 3600_000L

    return this.scan(AveragePowerState(0.0, ArrayDeque())) { state, (speed, grade) ->
        val now = currentTimeMillis()
        val newReadings = ArrayDeque(state.readings)
        var newSum = state.sum

        while (newReadings.isNotEmpty() && now - newReadings.first().timestamp >= oneHourInMillis) {
            newSum -= newReadings.removeFirst().power
        }

        val power = 0.5 * TravelTimeEstimationService.CDA * TravelTimeEstimationService.RHO_AIR * speed * speed * speed +
                totalWeight * TravelTimeEstimationService.G * (grade / 100.0 + TravelTimeEstimationService.CRR_PAVEMENT) * speed

        newReadings.addLast(PowerReading(now, power))
        AveragePowerState(newSum + power, newReadings)
    }.mapNotNull { state ->
        if (state.readings.isEmpty()) null else state.sum / state.readings.size
    }
}

/**
 * Builds a flow of average estimated power (W) from pre-processed speed (m/s)
 * and grade (%) flows. Exposed as `internal` for unit-testing purposes.
 */
internal fun buildEstimatedPowerFlow(
    totalWeight: Double,
    speedFlow: Flow<Double>,
    gradeFlow: Flow<Double>,
    currentTimeMillis: () -> Long = { System.currentTimeMillis() }
): Flow<Double> {
    return combine(speedFlow, gradeFlow) { speed, grade -> Pair(speed, grade) }
        .averagePowerOverHour(totalWeight, currentTimeMillis)
}

@OptIn(ExperimentalCoroutinesApi::class)
fun streamEstimatedPowerPerHour(
    totalWeight: Double,
    karooSystemServiceProvider: KarooSystemServiceProvider,
    currentTimeMillis: () -> Long = { System.currentTimeMillis() }
): Flow<Double> {
    val scope = CoroutineScope(Dispatchers.Default)

    val currentSpeedFlow = karooSystemServiceProvider.streamDataFlow(DataType.Type.SPEED)
        .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
    val currentGradeFlow = karooSystemServiceProvider.streamDataFlow(DataType.Type.ELEVATION_GRADE)
        .mapNotNull { (it as? StreamState.Streaming)?.dataPoint?.singleValue }
        .stateIn(scope, SharingStarted.Eagerly, 0.0)

    return buildEstimatedPowerFlow(totalWeight, currentSpeedFlow, currentGradeFlow, currentTimeMillis)
        .shareIn(scope, SharingStarted.WhileSubscribed())
}