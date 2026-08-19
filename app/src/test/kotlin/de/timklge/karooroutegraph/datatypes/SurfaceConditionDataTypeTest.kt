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

import de.timklge.karooroutegraph.SurfaceConditionRetrievalService
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceCondition
import de.timklge.karooroutegraph.SurfaceConditionRetrievalService.SurfaceConditionSegment
import de.timklge.karooroutegraph.datatypes.SurfaceConditionDataType.StreamData
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfaceConditionDataTypeTest {

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
    fun `returns NotAvailable when route distance is null`() {
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 100.0,
                routeDistance = null,
                surfaceConditions = emptyList()
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns NotAvailable when distance along route is null`() {
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = null,
                routeDistance = 10_000.0,
                surfaceConditions = emptyList()
            )
        )
        assertNull(result)
    }

    @Test
    fun `returns paved (0) when no surface conditions are available`() {
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 100.0,
                routeDistance = 10_000.0,
                surfaceConditions = null
            )
        )
        assertEquals(0, result)
    }

    @Test
    fun `returns paved (0) when rider is on a paved section between segments`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 500.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(0, result)
    }

    @Test
    fun `returns gravel (1) when rider is inside a gravel segment`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 1_500.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(1, result)
    }

    @Test
    fun `returns loose (2) when rider is inside a loose segment`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(5_000.0, 6_000.0, SurfaceCondition.LOOSE),
        )
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 5_500.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(2, result)
    }

    @Test
    fun `rider at exact segment start is considered inside the segment`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
        )
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 1_000.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(1, result)
    }

    @Test
    fun `rider at exact segment end is considered on the next segment`() {
        val conditions = listOf(
            segment(1_000.0, 2_000.0, SurfaceCondition.GRAVEL),
            segment(2_000.0, 3_000.0, SurfaceCondition.LOOSE),
        )
        val result = SurfaceConditionDataType.classifySurface(
            StreamData(
                distanceAlongRoute = 2_000.0,
                routeDistance = 10_000.0,
                surfaceConditions = conditions
            )
        )
        assertEquals(2, result)
    }

    @Test
    fun `classification value matches enum classificationValue property`() {
        assertEquals(1, SurfaceCondition.GRAVEL.classificationValue)
        assertEquals(2, SurfaceCondition.LOOSE.classificationValue)
    }

    @Test
    fun `getSurfaceTypeLabelRes maps 0 to asphalt string`() {
        assertEquals(de.timklge.karooroutegraph.R.string.surfacetype_asphalt,
            SurfaceConditionDataType.getSurfaceTypeLabelRes(0))
    }

    @Test
    fun `getSurfaceTypeLabelRes maps 1 to gravel string`() {
        assertEquals(de.timklge.karooroutegraph.R.string.surfacetype_gravel,
            SurfaceConditionDataType.getSurfaceTypeLabelRes(SurfaceCondition.GRAVEL.classificationValue))
    }

    @Test
    fun `getSurfaceTypeLabelRes maps 2 to loose string`() {
        assertEquals(de.timklge.karooroutegraph.R.string.surfacetype_loose,
            SurfaceConditionDataType.getSurfaceTypeLabelRes(SurfaceCondition.LOOSE.classificationValue))
    }

    @Test
    fun `getSurfaceTypeLabelRes maps unknown values to asphalt string`() {
        assertEquals(de.timklge.karooroutegraph.R.string.surfacetype_asphalt,
            SurfaceConditionDataType.getSurfaceTypeLabelRes(99))
        assertEquals(de.timklge.karooroutegraph.R.string.surfacetype_asphalt,
            SurfaceConditionDataType.getSurfaceTypeLabelRes(-1))
    }
}
