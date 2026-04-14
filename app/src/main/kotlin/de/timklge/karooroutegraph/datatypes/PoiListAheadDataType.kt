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
import androidx.core.graphics.drawable.DrawableCompat
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
import de.timklge.karooroutegraph.datatypes.utils.mapPoiToIcon
import de.timklge.karooroutegraph.pois.NearestPoint
import de.timklge.karooroutegraph.pois.NearbyPOI
import de.timklge.karooroutegraph.pois.OfflineNearbyPOIProvider
import de.timklge.karooroutegraph.pois.POI
import de.timklge.karooroutegraph.pois.PoiType
import de.timklge.karooroutegraph.pois.calculatePoiDistances
import de.timklge.karooroutegraph.screens.NearbyPoiCategory
import de.timklge.karooroutegraph.screens.RouteGraphPoiSettings
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PoiAheadEntry(
    val name: String,
    val distanceMeters: Double,
    val iconRes: Int
)

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
    private var cachedRouteKey: String? = null
    private var cachedPoiDistances: Map<POI, List<NearestPoint>>? = null
    private val glance = GlanceRemoteViews()
    private var cachedBitmap: Bitmap? = null

    private fun isNightMode(): Boolean {
        val nightModeFlags = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        streamJob = CoroutineScope(Dispatchers.Default).launch {
            viewModelProvider.viewModelFlow.collect { state ->
                val currentDistanceAlongRoute = state.distanceAlongRoute
                val routeLineString = state.knownRoute

                if (currentDistanceAlongRoute == null || routeLineString == null) {
                    poisAheadFlow.update { emptyList() }
                    lastPoiDistances = null
                    cachedRouteKey = null
                    cachedPoiDistances = null
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                // Get the active profile's POI categories
                val profileName = karooSystemServiceProvider.streamActiveKarooProfileName().first()
                val poiCategories: Set<NearbyPoiCategory> = if (profileName != null) {
                    try {
                        karooSystemServiceProvider.streamProfileViewSettings(profileName).first().autoAddPoiCategories
                    } catch (e: Exception) {
                        NearbyPoiCategory.entries.toSet()
                    }
                } else {
                    NearbyPoiCategory.entries.toSet()
                }

                val requestedTags = poiCategories.map { it.osmTag }.flatten()
                if (requestedTags.isEmpty()) {
                    poisAheadFlow.update { emptyList() }
                    emitter.onNext(StreamState.NotAvailable)
                    return@collect
                }

                val offlinePois = offlineNearbyPOIProvider.requestNearbyPOIs(
                    requestedTags,
                    routeLineString.coordinates(),
                    1000,
                    200
                )

                val poiSymbols = offlinePois.map { poi ->
                    val poiName = poi.tags["name"]
                        ?: NearbyPoiCategory.fromTag(poi.tags)?.let { applicationContext.getString(it.labelRes) }
                        ?: "Unnamed POI"
                    val category = NearbyPoiCategory.fromTag(poi.tags)
                    val poiType = category?.hhType ?: io.hammerhead.karooext.models.Symbol.POI.Types.GENERIC
                    POI(
                        symbol = io.hammerhead.karooext.models.Symbol.POI(
                            id = "ahead-${poi.id}",
                            lat = poi.lat,
                            lng = poi.lon,
                            name = poiName,
                            type = poiType
                        ),
                        type = PoiType.POI
                    )
                }

                val poiDistances = if (cachedRouteKey == routeLineString.hashCode().toString() && cachedPoiDistances != null) {
                    cachedPoiDistances!!
                } else {
                    val distances = calculatePoiDistances(
                        routeLineString,
                        poiSymbols,
                        1000.0
                    )
                    cachedRouteKey = routeLineString.hashCode().toString()
                    cachedPoiDistances = distances
                    distances
                }

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
                        Log.d(TAG, "PoiListAhead type='${poi.symbol.type}' name='${poi.symbol.name}' iconRes=$iconRes")
                        PoiAheadEntry(
                            name = poi.symbol.name ?: "Unnamed POI",
                            distanceMeters = dist.toDouble(),
                            iconRes = iconRes
                        )
                    }

                poisAheadFlow.update { entries }
                Log.d(TAG, "PoiListAhead: ${entries.size} POIs ahead: ${entries.map { "${it.name} (${it.iconRes})" }}")

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
        Log.d(TAG, "Starting POI list ahead view size=${config.viewSize.first}x${config.viewSize.second}")

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
            val iconColor = if (nightMode) Color.WHITE else Color.BLACK

            val namePaint = Paint().apply {
                color = textColor
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            val distancePaint = Paint().apply {
                color = secondaryColor
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
            }

            val emptyPaint = Paint().apply {
                color = secondaryColor
                textSize = 36f
                typeface = Typeface.DEFAULT
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            val iconSize = 44
            val iconLeft = 6f
            val nameX = iconLeft + iconSize + 8f
            val distanceX = (width - 6).toFloat()
            val nameEndX = distanceX - 120f
            val nameMaxWidth = nameEndX - nameX
            val itemHeight = 80f
            val startY = 8f

            poisAheadFlow.collect { pois ->
                val bitmap = cachedBitmap?.also { it.eraseColor(Color.TRANSPARENT) }
                    ?: createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cachedBitmap = it }
                val canvas = Canvas(bitmap)

                canvas.drawColor(backgroundColor)

                // Draw icon at given position with tint
                fun drawIcon(canvas: Canvas, iconRes: Int, left: Int, top: Int, size: Int) {
                    val drawable = AppCompatResources.getDrawable(context, iconRes)?.mutate()
                    if (drawable != null) {
                        DrawableCompat.setTint(drawable, iconColor)
                        drawable.setBounds(left, top, left + size, top + size)
                        drawable.draw(canvas)
                    }
                }

                // Calculate text center positions
                val nameMetrics = namePaint.fontMetrics
                val nameCenterOffset = (nameMetrics.bottom - nameMetrics.top) / 2 - nameMetrics.bottom
                val distanceMetrics = distancePaint.fontMetrics
                val distanceCenterOffset = (distanceMetrics.bottom - distanceMetrics.top) / 2 - distanceMetrics.bottom

                if (pois.isEmpty()) {
                    canvas.drawText("No POIs ahead", width / 2f, height / 2f, emptyPaint)
                } else {
                    pois.forEachIndexed { index, entry ->
                        val itemTop = startY + index * itemHeight
                        val verticalCenter = itemTop + itemHeight / 2f
                        val iconTop = (verticalCenter - iconSize / 2f).toInt()
                        val iconLeftPos = iconLeft.toInt()

                        // Draw icon
                        drawIcon(canvas, entry.iconRes, iconLeftPos, iconTop, iconSize)

                        // Calculate distance text
                        val distanceText = if (entry.distanceMeters >= 1000) {
                            "%.1f km".format(entry.distanceMeters / 1000.0)
                        } else {
                            "${entry.distanceMeters.roundToInt()} m"
                        }

                        // Draw name (single line, truncate if too long)
                        val nameBaseline = verticalCenter + nameCenterOffset
                        var displayName = entry.name

                        // Measure and truncate with ellipsis
                        val ellipsisWidth = namePaint.measureText("…")
                        while (displayName.length > 1 && namePaint.measureText(displayName) + ellipsisWidth > nameMaxWidth) {
                            displayName = displayName.dropLast(1)
                        }
                        if (displayName != entry.name) {
                            displayName = "$displayName…"
                        }

                        // Clip to prevent overlap with distance text
                        val clipRect = android.graphics.RectF(nameX, itemTop, nameEndX, itemTop + itemHeight)
                        canvas.save()
                        canvas.clipRect(clipRect)
                        canvas.drawText(displayName, nameX, nameBaseline, namePaint)
                        canvas.restore()

                        // Draw distance - vertically centered
                        val distanceBaseline = verticalCenter + distanceCenterOffset
                        canvas.drawText(distanceText, distanceX, distanceBaseline, distancePaint)
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
