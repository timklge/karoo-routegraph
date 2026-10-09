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

import de.timklge.karooroutegraph.screens.RouteGraphSettings
import org.junit.Test
import kotlin.test.assertEquals

class RouteGraphDisplayViewModelTest {

    private val defaultSettings = RouteGraphSettings()

    private fun viewModel(routeDistance: Float? = null, isImperial: Boolean = false) =
        RouteGraphViewModel(routeDistance = routeDistance, isImperial = isImperial)

    @Test
    fun `the complete route level shows the whole route`() {
        assertEquals(12_000f, ZoomLevel.CompleteRoute.getDistanceInMeters(viewModel(routeDistance = 12_000f), defaultSettings))
        assertEquals(null, ZoomLevel.CompleteRoute.getDistanceInMeters(viewModel(), defaultSettings))
    }

    @Test
    fun `a unit level shows its distance in the rider's units`() {
        assertEquals(5_000f, ZoomLevel.Units(5).getDistanceInMeters(viewModel(), defaultSettings))
        assertEquals(5 * 1609.34f, ZoomLevel.Units(5).getDistanceInMeters(viewModel(isImperial = true), defaultSettings))
    }

    @Test
    fun `zooming in from the complete route uses the closest configured level`() {
        assertEquals(ZoomLevel.Units(2), ZoomLevel.CompleteRoute.next(viewModel(), defaultSettings))
        assertEquals(ZoomLevel.Units(2), ZoomLevel.CompleteRoute.next(viewModel(routeDistance = 90_000f), defaultSettings))
    }

    @Test
    fun `zooming in from the complete route stays put when no levels are configured`() {
        val settings = defaultSettings.copy(elevationProfileZoomLevels = emptyList())

        assertEquals(ZoomLevel.CompleteRoute, ZoomLevel.CompleteRoute.next(viewModel(routeDistance = 90_000f), settings))
    }

    @Test
    fun `zooming out from a unit level picks the next configured level`() {
        assertEquals(ZoomLevel.Units(10), ZoomLevel.Units(2).next(viewModel(), defaultSettings))
        assertEquals(ZoomLevel.Units(10), ZoomLevel.Units(2).next(viewModel(routeDistance = 90_000f), defaultSettings))
    }

    @Test
    fun `zooming out past the last level goes back to the complete route`() {
        assertEquals(ZoomLevel.CompleteRoute, ZoomLevel.Units(50).next(viewModel(), defaultSettings))
    }

    @Test
    fun `zooming out never passes the end of a short route`() {
        assertEquals(ZoomLevel.CompleteRoute, ZoomLevel.Units(2).next(viewModel(routeDistance = 5_000f), defaultSettings))
        assertEquals(ZoomLevel.Units(10), ZoomLevel.Units(2).next(viewModel(routeDistance = 30_000f), defaultSettings))
    }

    @Test
    fun `a custom level set is used in order`() {
        val settings = defaultSettings.copy(elevationProfileZoomLevels = listOf(1, 3, 8))

        assertEquals(ZoomLevel.Units(3), ZoomLevel.Units(1).next(viewModel(), settings))
        assertEquals(ZoomLevel.Units(8), ZoomLevel.Units(3).next(viewModel(), settings))
        assertEquals(ZoomLevel.CompleteRoute, ZoomLevel.Units(8).next(viewModel(), settings))
    }
}
