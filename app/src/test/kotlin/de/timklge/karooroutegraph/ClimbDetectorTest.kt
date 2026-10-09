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

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimbDetectorTest {

    private fun graded(count: Int, interval: Float = 60f, grade: Float = 0.05f, base: Float = 100f) =
        SampledElevationData(interval, FloatArray(count) { i -> base + i * interval * grade })

    @Test
    fun `categorize picks the largest category the gradient reaches`() {
        assertEquals(ClimbCategory.LARGE_CLIMB, ClimbCategory.categorize(0.20f, 1_000f))
        assertEquals(ClimbCategory.MEDIUM_CLIMB, ClimbCategory.categorize(0.10f, 1_000f))
        assertEquals(ClimbCategory.MEDIUM_SMALL_CLIMB, ClimbCategory.categorize(0.05f, 1_000f))
        assertEquals(ClimbCategory.SMALL_CLIMB, ClimbCategory.categorize(0.01f, 1_000f))
        assertEquals(ClimbCategory.SMALL_CLIMB, ClimbCategory.categorize(0f, 1_000f))
    }

    @Test
    fun `categorize returns null for a descent`() {
        assertNull(ClimbCategory.categorize(-0.01f, 1_000f))
    }

    @Test
    fun `categorize boundaries belong to the larger category`() {
        assertEquals(ClimbCategory.LARGE_CLIMB, ClimbCategory.categorize(0.125f, 1_000f))
        assertEquals(ClimbCategory.MEDIUM_CLIMB, ClimbCategory.categorize(0.076f, 1_000f))
        assertEquals(ClimbCategory.MEDIUM_SMALL_CLIMB, ClimbCategory.categorize(0.046f, 1_000f))
    }

    @Test
    fun `climb length is the distance between its ends`() {
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 100, 400)

        assertEquals(300f, climb.length)
    }

    @Test
    fun `climb total gain sums the ascent over its range`() {
        val data = graded(count = 6, grade = 0.05f)
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 0, 300)

        assertEquals(15.0, climb.totalGain(data), absoluteTolerance = 0.001)
    }

    @Test
    fun `climb average incline matches the profile gradient`() {
        val data = graded(count = 6, grade = 0.05f)
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 60, 300)

        assertEquals(0.05, climb.getAverageIncline(data), absoluteTolerance = 0.0001)
    }

    @Test
    fun `average incline of an empty range is zero`() {
        val data = graded(count = 6, grade = 0.05f)
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 5_000, 6_000)

        assertEquals(0.0, climb.getAverageIncline(data), absoluteTolerance = 0.001)
    }

    @Test
    fun `max incline is found on a steady climb`() {
        val data = graded(count = 6, grade = 0.05f)
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 0, 300)

        val maxIncline = climb.getMaxIncline(data)

        assertEquals(5, maxIncline.incline)
        assertEquals(60f, maxIncline.end - maxIncline.start)
    }

    @Test
    fun `max incline ignores a descent inside the climb`() {
        val data = SampledElevationData(60f, floatArrayOf(100f, 130f, 120f, 150f, 180f, 210f))
        val climb = Climb(ClimbCategory.SMALL_CLIMB, 0, 300)

        val maxIncline = climb.getMaxIncline(data)

        assertEquals(50, maxIncline.incline)
        assertEquals(240f, maxIncline.start)
        assertEquals(300f, maxIncline.end)
    }
}
