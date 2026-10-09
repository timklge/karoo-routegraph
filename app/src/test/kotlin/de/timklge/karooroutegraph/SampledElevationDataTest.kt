/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.timklge.karooroutegraph

import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampledElevationDataTest {

    /** Flat 1 km route with 60 m samples, climbing [grade] (as a fraction) each sample. */
    private fun sampled(count: Int = 11, interval: Float = 60f, grade: Float = 0f): SampledElevationData =
        SampledElevationData(interval, FloatArray(count) { i -> 100f + i * interval * grade })

    /** Straight west-to-east route of [meters] at the equator, for turf measurements. */
    private fun straightRoute(meters: Double): LineString {
        val degreesPerMeter = 1.0 / 111_319.49
        return LineString.fromLngLats(
            listOf(
                Point.fromLngLat(0.0, 0.0),
                Point.fromLngLat(meters * degreesPerMeter, 0.0)
            )
        )
    }

    @Test
    fun `total climb sums only the ascending intervals inside the range`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 110f, 120f, 130f))

        assertEquals(30.0, data.getTotalClimb(0f, 180f), absoluteTolerance = 0.001)
        assertEquals(20.0, data.getTotalClimb(0f, 120f), absoluteTolerance = 0.001)
        assertEquals(10.0, data.getTotalClimb(0f, 60f), absoluteTolerance = 0.001)
    }

    @Test
    fun `total climb ignores descending intervals`() {
        val data = SampledElevationData(60f, floatArrayOf(130f, 120f, 110f))

        assertEquals(0.0, data.getTotalClimb(0f, 120f), absoluteTolerance = 0.001)
    }

    @Test
    fun `total climb counts only the climbs of a rolling profile`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 120f, 110f, 140f))

        assertEquals(50.0, data.getTotalClimb(0f, 180f), absoluteTolerance = 0.001)
    }

    @Test
    fun `minimum and maximum elevation are taken over the interval endpoints`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 130f, 110f, 120f))

        assertEquals(100f, data.getMinimumElevationInRange(0f, 120f))
        assertEquals(130f, data.getMaximumElevationInRange(0f, 120f))
        assertEquals(110f, data.getMinimumElevationInRange(120f, 180f))
        assertEquals(120f, data.getMaximumElevationInRange(120f, 180f))
    }

    @Test
    fun `maximum incline keeps the sign of the steepest segment`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 94f, 106f))

        assertEquals(0.2f, data.getMaximumInclineInRange(0f, 120f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `maximum incline ignores segments outside the range`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 100f, 160f))

        assertEquals(0f, data.getMaximumInclineInRange(0f, 60f), absoluteTolerance = 0.0001f)
        assertEquals(1f, data.getMaximumInclineInRange(60f, 120f), absoluteTolerance = 0.0001f)
    }

    @Test
    fun `sparse elevation data is interpolated onto a regular grid`() {
        val sparse = LineString.fromLngLats(
            listOf(
                Point.fromLngLat(100.0, 0.0),
                Point.fromLngLat(160.0, 120.0)
            )
        )

        val sampled = SampledElevationData.fromSparseElevationData(sparse, interval = 60f)

        assertEquals(60f, sampled.interval)
        assertEquals(3, sampled.elevations.size)
        assertEquals(100f, sampled.elevations[0])
        assertEquals(130f, sampled.elevations[1])
        assertEquals(160f, sampled.elevations[2])
    }

    @Test
    fun `sparse elevation data with a single point keeps that elevation`() {
        val sparse = LineString.fromLngLats(listOf(Point.fromLngLat(250.0, 0.0)))

        val sampled = SampledElevationData.fromSparseElevationData(sparse, interval = 60f)

        assertEquals(1, sampled.elevations.size)
        assertEquals(250f, sampled.elevations[0])
    }

    @Test
    fun `empty sparse elevation data yields no samples`() {
        val sampled = SampledElevationData.fromSparseElevationData(LineString.fromLngLats(emptyList()), interval = 60f)

        assertEquals(0, sampled.elevations.size)
    }

    @Test
    fun `gradient indicators are emitted once per step at the segment start`() {
        val data = sampled(count = 11, grade = 0.05f)
        val route = straightRoute(700.0)

        val indicators = data.getGradientIndicators(route, stepInMeters = 200f) { true }

        assertEquals(listOf(0f, 200f, 400f), indicators.map { it.distance })
        assertTrue(indicators.all { it.gradientPercent == 5f })
        assertEquals(3, indicators.map { it.id }.toSet().size)
    }

    @Test
    fun `gradient indicators are filtered by the range predicate`() {
        val data = sampled(count = 11, grade = 0.05f)
        val route = straightRoute(700.0)

        val indicators = data.getGradientIndicators(route, stepInMeters = 200f) { it >= 200f }

        assertEquals(listOf(200f, 400f), indicators.map { it.distance })
    }

    @Test
    fun `a flat route draws no gradient indicators`() {
        val data = sampled(count = 11, grade = 0f)
        val route = straightRoute(700.0)

        assertTrue(data.getGradientIndicators(route, stepInMeters = 200f) { true }.isEmpty())
    }

    @Test
    fun `an invalid step or interval draws nothing`() {
        val data = sampled(count = 11, grade = 0.05f)
        val route = straightRoute(700.0)

        assertTrue(data.getGradientIndicators(route, stepInMeters = 0f) { true }.isEmpty())
        assertTrue(SampledElevationData(0f, data.elevations).getGradientIndicators(route, 200f) { true }.isEmpty())
    }
}
