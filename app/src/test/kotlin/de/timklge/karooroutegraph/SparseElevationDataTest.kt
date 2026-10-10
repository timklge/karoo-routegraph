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
import kotlin.test.assertTrue

class SparseElevationDataTest {

    @Test
    fun `samples between sparse points are linearly interpolated`() {
        val sparse = SparseElevationData(
            distances = floatArrayOf(0f, 100f, 200f),
            elevations = floatArrayOf(100f, 110f, 130f)
        )

        val sampled = sparse.toSampledElevationData(interval = 50f)

        assertEquals(50f, sampled.interval)
        assertEquals(listOf(100f, 105f, 110f, 120f, 130f), sampled.elevations.toList())
    }

    @Test
    fun `the sample grid covers the last sparse distance`() {
        val sparse = SparseElevationData(
            distances = floatArrayOf(0f, 300f),
            elevations = floatArrayOf(10f, 40f)
        )

        val sampled = sparse.toSampledElevationData(interval = 100f)

        assertEquals(4, sampled.elevations.size)
        assertEquals(40f, sampled.elevations.last())
    }

    @Test
    fun `sparse samples on the grid are reproduced exactly`() {
        val sparse = SparseElevationData(
            distances = floatArrayOf(0f, 100f, 200f),
            elevations = floatArrayOf(5f, 25f, 45f)
        )

        val sampled = sparse.toSampledElevationData(interval = 100f)

        assertEquals(listOf(5f, 25f, 45f), sampled.elevations.toList())
    }

    @Test
    fun `interpolated samples stay within the sparse elevation range`() {
        val sparse = SparseElevationData(
            distances = floatArrayOf(0f, 60f, 120f, 180f),
            elevations = floatArrayOf(100f, 90f, 130f, 120f)
        )

        val sampled = sparse.toSampledElevationData(interval = 20f)

        assertTrue(sampled.elevations.all { it in 90f..130f })
    }
}
