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

import fi.nikosavola.karooext.testing.FakeKarooSystem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistanceFormattingTest {

    private val metric = FakeKarooSystem.metricProfile()
    private val imperial = FakeKarooSystem.imperialProfile()

    @Test
    fun `metric distances use metres until a full kilometre`() {
        assertEquals("0 m", distanceToString(0f, isImperial = false, onlyMinorUnit = false))
        assertEquals("999 m", distanceToString(999f, isImperial = false, onlyMinorUnit = false))
        assertEquals("1000 m", distanceToString(1_000f, isImperial = false, onlyMinorUnit = false))
        assertEquals("1 km", distanceToString(1_500f, isImperial = false, onlyMinorUnit = false))
        assertEquals("12 km", distanceToString(12_400f, isImperial = false, onlyMinorUnit = false))
    }

    @Test
    fun `onlyMinorUnit keeps the small unit past a kilometre`() {
        assertEquals("1500 m", distanceToString(1_500f, isImperial = false, onlyMinorUnit = true))
        assertEquals("2640 ft", distanceToString(804.672f, isImperial = true, onlyMinorUnit = true))
    }

    @Test
    fun `imperial distances use feet until a full mile`() {
        assertEquals("328 ft", distanceToString(100f, isImperial = true, onlyMinorUnit = false))
        assertEquals("5249 ft", distanceToString(1_600f, isImperial = true, onlyMinorUnit = false))
        assertEquals("2 mi", distanceToString(3_218.688f, isImperial = true, onlyMinorUnit = false))
    }

    @Test
    fun `a distance below the smaller unit counts as zero`() {
        assertTrue(distanceIsZero(0.4f, metric))
        assertFalse(distanceIsZero(1f, metric))
        assertFalse(distanceIsZero(999f, metric))

        assertTrue(distanceIsZero(0.1f, imperial))
        assertFalse(distanceIsZero(100f, imperial))
    }
}
