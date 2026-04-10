package de.timklge.karooroutegraph.datatypes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.unit.DpSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.RouteGraphViewModelProvider
import de.timklge.karooroutegraph.KarooSystemServiceProvider
import de.timklge.karooroutegraph.datatypes.minimap.mapPoiToIcon
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.NearbyPOI
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PoiAheadEntry(
    val name: String,
    val distanceMeters: Double,
    val iconRes: Int
)

/**
 * Data field that shows the next 5 POIs ahead on the route.
 * Renders as a graphical view with Canvas drawing.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class PoiListAheadDataType(
    private val karooSystem: KarooSystemService,
    private val viewModelProvider: RouteGraphViewModelProvider,
    private val karooSystemServiceProvider: KarooSystemServiceProvider,
    private val offlineNearbyPOIProvider: OfflineNearbyPOIProvider,
    private val applicationContext: Context
) : DataTypeImpl("karoo-routegraph", "poilistahead") {

    private var streamJob: Job? = null
    private var viewJob: Job? = null
    private val poisAheadFlow = MutableStateFlow<List<PoiAheadEntry>>(emptyList())
    private var lastPoiDistances: Map<POI, List<NearestPoint>>? = null
    private val glance = GlanceRemoteViews()

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            // Fetch POIs independently using the same categories as the offline POI provider
            val allCategories = NearbyPoiCategory.entries.map { it.osmTag }.flatten()

            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute
                val routeLineString = state.knownRoute

                if (currentDistanceAlongRoute == null || routeLineString == null) {
                    poisAheadFlow.update { emptyList() }
                    lastPoiDistances = null
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                // Fetch offline POIs independently
                val offlinePois = offlineNearbyPOIProvider.requestNearbyPOIs(
                    allCategories,
                    routeLineString.coordinates(),
                    1000,
                    200
                )

                Log.d(TAG, "PoiListAhead: fetched ${offlinePois.size} offline POIs")

                val poiSymbols = offlinePois.map { poi ->
                    val poiName = poi.tags["name"]
                        ?: NearbyPoiCategory.fromTag(poi.tags)?.let { applicationContext.getString(it.labelRes) }
                        ?: "Unnamed POI"
                    POI(
                        symbol = io.hammerhead.karooext.models.Symbol.POI(
                            id = "ahead-${poi.id}",
                            lat = poi.lat,
                            lng = poi.lon,
                            name = poiName,
                            type = NearbyPoiCategory.fromTag(poi.tags)?.hhType
                                ?: io.hammerhead.karooext.models.Symbol.POI.Types.GENERIC
                        ),
                        type = PoiType.POI
                    )
                }

                // Calculate distances
                val poiDistances = calculatePoiDistances(
                    routeLineString,
                    poiSymbols,
                    1000.0
                )

                lastPoiDistances = poiDistances

                if (poiDistances.isEmpty()) {
                    poisAheadFlow.update { emptyList() }
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val entries = poiDistances.entries
                    .flatMap { (poi, list) -> list.map { distance -> poi to distance } }
                    .filter { (_, distance) ->
                        distance.distanceFromRouteStart - currentDistanceAlongRoute > 0
                    }
                    .sortedBy { (_, distance) -> distance.distanceFromRouteStart }
                    .take(5)
                    .map { (poi, distance) ->
                        val dist = distance.distanceFromRouteStart - currentDistanceAlongRoute
                        val iconRes = mapPoiToIcon(poi.symbol.type)
                        PoiAheadEntry(
                            name = poi.symbol.name ?: "Unnamed POI",
                            distanceMeters = dist.toDouble(),
                            iconRes = iconRes
                        )
                    }

                poisAheadFlow.update { entries }
                Log.d(TAG, "PoiListAhead: ${entries.size} POIs ahead")

                // Emit a dummy value to keep the stream alive
                val firstDist = entries.firstOrNull()?.distanceMeters ?: 0.0
                emitter.onNext(StreamState.Streaming(
                    DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to firstDist))
                ))
            }
        }
        emitter.setCancellable {
            streamJob?.cancel()
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "Starting POI list ahead view with $emitter")

        val configJob = CoroutineScope(Dispatchers.Default).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        viewJob = CoroutineScope(Dispatchers.Default).launch {
            val width = config.viewSize.first
            val height = config.viewSize.second

            if (width <= 0 || height <= 0) {
                awaitCancellation()
                return@launch
            }

            val nightMode = isNightMode()
            val backgroundColor = if (nightMode) Color.BLACK else Color.WHITE
            val textColor = if (nightMode) Color.WHITE else Color.BLACK
            val secondaryColor = if (nightMode) Color.LTGRAY else Color.DKGRAY

            val namePaint = Paint().apply {
                color = textColor
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            val distancePaint = Paint().apply {
                color = secondaryColor
                textSize = 30f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
            }

            val emptyPaint = Paint().apply {
                color = secondaryColor
                textSize = 34f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            poisAheadFlow.collect { pois ->
                val iconSize = 28f
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)

                // Clear background
                canvas.drawColor(backgroundColor)

                if (pois.isEmpty()) {
                    canvas.drawText("No POIs ahead", width / 2f, height / 2f, emptyPaint)
                } else {
                    val lineHeight = 42f
                    val startY = 36f
                    val iconPadding = 4f
                    val nameX = iconSize + iconPadding + 4f
                    val distanceX = (width - 10).toFloat()

                    pois.forEachIndexed { index, entry ->
                        val y = startY + index * lineHeight

                        val distanceText = if (entry.distanceMeters >= 1000) {
                            "%.1f km".format(entry.distanceMeters / 1000.0)
                        } else {
                            "${entry.distanceMeters.roundToInt()} m"
                        }

                        // Draw icon
                        val icon = AppCompatResources.getDrawable(applicationContext, entry.iconRes)
                        val iconY = (y - iconSize).toInt()
                        val iconRect = Rect(4, iconY, 4 + iconSize.toInt(), iconY + iconSize.toInt())
                        val iconBitmap = icon?.toBitmap(iconSize.toInt(), iconSize.toInt())
                        if (iconBitmap != null) {
                            canvas.drawBitmap(iconBitmap, null, iconRect, null)
                        }

                        // Truncate name if too long
                        val maxWidth = width - iconSize - iconPadding - 100f
                        var displayName = entry.name
                        if (namePaint.measureText(displayName) > maxWidth) {
                            while (namePaint.measureText("$displayName…") > maxWidth && displayName.length > 1) {
                                displayName = displayName.dropLast(1)
                            }
                            displayName = "$displayName…"
                        }

                        canvas.drawText(displayName, nameX, y, namePaint)
                        canvas.drawText(distanceText, distanceX, y, distancePaint)

                        // Move icon rect down for next iteration
                        iconRect.offset(0, lineHeight.toInt())
                    }
                }

                val result = glance.compose(context, DpSize.Unspecified) {
                    Box(modifier = GlanceModifier.fillMaxSize()) {
                        Image(ImageProvider(bitmap), "POI List", modifier = GlanceModifier.fillMaxSize())
                    }
                }
                emitter.updateView(result.remoteViews)
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob?.cancel()
        }
    }
}
