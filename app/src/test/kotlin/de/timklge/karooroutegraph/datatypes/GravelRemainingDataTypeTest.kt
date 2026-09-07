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

package de.timklge.karooroutegraph.datatypes

import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceCondition
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceConditionSegment
import de.timklge.karooroutegraph.datatypes.GravelRemainingDataType.StreamData
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GravelRemainingDataTypeTest {

    private fun segment(
        startM: Double,
        endM: Double,
        condition: SurfaceCondition,
    ) = SurfaceConditionSegment(
        startMeters = startM,
        endMeters = endM,
        condition = condition,
        samples = 10
    )

    @Test
    fun `returns null when route distance is null`() {
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 100.0,
                routeDistance = null,
                surfaceConditions = emptyList()
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns null when distance along route is null`() {
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = null,
                routeDistance = 10_000.0,
                surfaceConditions = emptyList()
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns null when surface conditions have not been calculated`() {
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 100.0,
                routeDistance = 10_000.0,
                surfaceConditions = null
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns zero when there are no offroad segments`() {
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 100.0,
                routeDistance = 10_000.0,
                surfaceConditions = emptyList()
            )
        )
        assertEquals(0.0, result)
    }

    @Test
    fun `sums gravel and loose segments ahead of the rider`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 500.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(2_000.0, result)
    }

    @Test
    fun `clips the segment containing the rider to its remaining part`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 1_500.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(1_500.0, result)
    }

    @Test
    fun `ignores segments fully behind the rider`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 3_000.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(1_000.0, result)
    }

    @Test
    fun `returns zero when rider is past all offroad segments`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 9_000.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(0.0, result)
    }

    @Test
    fun `rider at exact segment end is past the segment`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
        )
        val result = GravelRemainingDataType.calculateRemainingOffroadDistance(
            StreamData(
                distanceAlongRoute = 2_000.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(0.0, result)
    }
}
