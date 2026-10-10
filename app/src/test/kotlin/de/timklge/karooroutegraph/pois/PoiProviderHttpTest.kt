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

package de.timklge.karooroutegraph.pois

import android.app.Application
import com.mapbox.geojson.Point
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import fi.nikosavola.karooext.testing.HttpResponses
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the POI search providers through the Karoo HTTP bridge, so the requests the extension
 * builds and its parsing of the answers are both covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PoiProviderHttpTest {
    @get:Rule val karoo = FakeKarooRule()

    private lateinit var provider: KarooSystemServiceProvider

    @Before
    fun connect() {
        provider = KarooSystemServiceProvider(karoo.app)
        karoo.awaitValue { provider.karooSystemService.connected.takeIf { it } }
    }

    @After
    fun tearDown() {
        provider.karooSystemService.disconnect()
    }

    /** Runs [block] on a worker thread so the test thread can pump the looper while it waits. */
    private fun <T> call(block: suspend () -> T): Result<T> {
        val pending = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        return karoo.awaitValue(20_000) { pending.takeIf { it.isCompleted }?.getCompleted() }
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private val nominatimJson = """
        [
          {
            "place_id": 1,
            "licence": "ODbL",
            "osm_type": "node",
            "osm_id": 2,
            "lat": "60.17",
            "lon": "24.94",
            "class": "amenity",
            "type": "cafe",
            "place_rank": 30,
            "importance": 0.5,
            "addresstype": "cafe",
            "name": "Cafe",
            "display_name": "Cafe, Helsinki",
            "boundingbox": ["60.1", "60.2", "24.9", "25.0"]
          }
        ]
    """.trimIndent()

    private val overpassJson = """
        {
          "version": 0.6,
          "generator": "Overpass API",
          "elements": [
            {"type": "node", "id": 42, "lat": 60.171, "lon": 24.941,
             "tags": {"amenity": "drinking_water", "name": "Fountain"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `a nominatim search returns the parsed places`() {
        karoo.system.responder = HttpResponses.success(nominatimJson.toByteArray())

        val places = call { NominatimProvider(provider).requestNominatim("Helsinki") }.getOrThrow()

        assertEquals(1, places.size)
        assertEquals("Cafe, Helsinki", places.single().displayName)
        assertEquals("60.17", places.single().lat)
        assertEquals("24.94", places.single().lon)

        val request = karoo.system.httpRequestsTo("nominatim").single()
        assertEquals("GET", request.method)
        assertTrue(request.url.contains("nominatim.openstreetmap.org/search?q=Helsinki&limit=10&format=json"), request.url)
        assertEquals("karoo-routegraph", request.headers["User-Agent"])
        assertEquals("gzip", request.headers["Accept-Encoding"])
    }

    @Test
    fun `a gzipped nominatim answer is decompressed`() {
        karoo.system.responder = HttpResponses.success(
            gzip(nominatimJson),
            headers = mapOf("Content-Encoding" to "gzip")
        )

        val places = call { NominatimProvider(provider).requestNominatim("Helsinki") }.getOrThrow()

        assertEquals(1, places.size)
    }

    @Test
    fun `a search query is escaped in the request url`() {
        karoo.system.responder = HttpResponses.success("[]".toByteArray())

        call { NominatimProvider(provider).requestNominatim("Cafe & Bar", limit = 3) }.getOrThrow()

        val url = karoo.system.httpRequestsTo("nominatim").single().url
        assertTrue(url.contains("q=Cafe+%26+Bar"), url)
        assertTrue(url.contains("limit=3"), url)
    }

    @Test
    fun `a transport failure reaches the caller`() {
        karoo.system.responder = HttpResponses.failure("no network")

        val failure = call { NominatimProvider(provider).requestNominatim("Helsinki") }.exceptionOrNull()

        assertTrue(failure != null && failure.message.orEmpty().contains("no network"), "got $failure")
    }

    @Test
    fun `an unreadable nominatim answer fails the call`() {
        karoo.system.responder = HttpResponses.success("<html>not json</html>".toByteArray())

        val failure = call { NominatimProvider(provider).requestNominatim("Helsinki") }.exceptionOrNull()

        assertTrue(failure != null, "expected the parse failure to reach the caller")
    }

    @Test
    fun `an overpass search maps elements to nearby POIs`() {
        karoo.system.responder = HttpResponses.success(overpassJson.toByteArray())

        val pois = call {
            OverpassPOIProvider(provider).requestNearbyPOIs(
                requestedTags = listOf("amenity" to "drinking_water"),
                points = listOf(Point.fromLngLat(24.94, 60.17), Point.fromLngLat(24.95, 60.18)),
                radius = 1_000,
                limit = 20
            )
        }.getOrThrow()

        assertEquals(1, pois.size)
        assertEquals(42L, pois.single().id)
        assertEquals("Fountain", pois.single().tags["name"])
    }

    @Test
    fun `an overpass query carries its tags, radius and polyline`() {
        karoo.system.responder = HttpResponses.success(overpassJson.toByteArray())

        call {
            OverpassPOIProvider(provider).requestNearbyPOIs(
                requestedTags = listOf("amenity" to "drinking_water"),
                points = listOf(Point.fromLngLat(24.94, 60.17), Point.fromLngLat(24.95, 60.18)),
                radius = 1_000,
                limit = 20
            )
        }.getOrThrow()

        val request = karoo.system.httpRequestsTo("overpass-api.de").single()
        assertEquals("POST", request.method)
        val body = URLDecoder.decode(String(request.body!!), "UTF-8")
        assertTrue(body.startsWith("data=[out:json];(node[amenity=drinking_water](around:1000,"), body)
        assertTrue(body.endsWith(");out center 20;"), body)
    }

    @Test
    fun `overpass reports an overloaded server`() {
        karoo.system.responder = HttpResponses.status(504)

        val failure = call {
            OverpassPOIProvider(provider).requestNearbyPOIs(emptyList(), listOf(Point.fromLngLat(24.94, 60.17)), 1_000, 20)
        }.exceptionOrNull()

        assertTrue(failure != null && failure.message.orEmpty().contains("overloaded"), "got $failure")
    }

    @Test
    fun `overpass reports a rate limit`() {
        karoo.system.responder = HttpResponses.status(429)

        val failure = call {
            OverpassPOIProvider(provider).requestNearbyPOIs(emptyList(), listOf(Point.fromLngLat(24.94, 60.17)), 1_000, 20)
        }.exceptionOrNull()

        assertTrue(failure != null && failure.message.orEmpty().contains("rate limit"), "got $failure")
    }

    @Test
    fun `overpass reports a server error`() {
        karoo.system.responder = HttpResponses.status(500)

        val failure = call {
            OverpassPOIProvider(provider).requestNearbyPOIs(emptyList(), listOf(Point.fromLngLat(24.94, 60.17)), 1_000, 20)
        }.exceptionOrNull()

        assertTrue(failure != null && failure.message.orEmpty().contains("server error"), "got $failure")
    }

    @Test
    fun `an unreadable overpass answer fails the call`() {
        karoo.system.responder = HttpResponses.success("not json".toByteArray())

        val failure = call {
            OverpassPOIProvider(provider).requestNearbyPOIs(emptyList(), listOf(Point.fromLngLat(24.94, 60.17)), 1_000, 20)
        }.exceptionOrNull()

        assertTrue(failure != null, "expected the parse failure to reach the caller")
    }
}
