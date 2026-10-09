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

class InclineColorsTest {

    @Test
    fun `descents pick the chevron of their gradient band`() {
        assertEquals(R.drawable.chevrondown2, getInclineIndicator(-8.1f))
        assertEquals(R.drawable.chevrondown1, getInclineIndicator(-8f))
        assertEquals(R.drawable.chevrondown0, getInclineIndicator(-5f))
        assertEquals(R.drawable.chevrondown0, getInclineIndicator(-2.1f))
    }

    @Test
    fun `climbs pick the chevron of their gradient band`() {
        assertEquals(R.drawable.chevron1, getInclineIndicator(1f))
        assertEquals(R.drawable.chevron0, getInclineIndicator(2f))
        assertEquals(R.drawable.chevron2, getInclineIndicator(5f))
        assertEquals(R.drawable.chevron3, getInclineIndicator(8f))
        assertEquals(R.drawable.chevron4, getInclineIndicator(11f))
        assertEquals(R.drawable.chevron5, getInclineIndicator(14f))
        assertEquals(R.drawable.chevron6, getInclineIndicator(20f))
        assertEquals(R.drawable.chevron6, getInclineIndicator(40f))
    }

    @Test
    fun `gentle gradients draw no chevron`() {
        assertNull(getInclineIndicator(0f))
        assertNull(getInclineIndicator(-1.9f))
        assertNull(getInclineIndicator(0.9f))
    }

    @Test
    fun `every chevron has a matching color band`() {
        for (percent in listOf(-30f, -8f, -5f, -2f, 1f, 2f, 5f, 8f, 11f, 14f, 20f, 30f)) {
            assertEquals(
                getInclineIndicator(percent) != null,
                getInclineIndicatorColor(percent) != null,
                "indicator and color disagree at $percent%"
            )
        }
    }
}
