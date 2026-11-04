package com.bbpillow.haku.platform.map

import android.app.Application
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.timeline.MapFit
import com.bbpillow.haku.surface.timeline.TimelineMap
import com.bbpillow.haku.surface.timeline.TimelineMapPoint
import com.bbpillow.haku.surface.timeline.TimelineMapState
import java.io.File
import kotlin.math.ceil
import org.mapsforge.core.graphics.Bitmap as MfBitmap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes

private val StopColors = intArrayOf(
  0xFFF0705A.toInt(),
  0xFF7C5CF0.toInt(),
  0xFFD9962B.toInt(),
  0xFF2E9E6E.toInt(),
)

/**
 * Real offline map card under timeline stops (Android, Mapsforge 0.25.0).
 * Tile-free fallback is handled via [onUnavailable].
 */
@Composable
fun OfflineDayMap(
  map: TimelineMapState,
  mapFile: File,
  modifier: Modifier = Modifier,
  onUnavailable: () -> Unit = {},
) {
  val currentOnUnavailable by rememberUpdatedState(onUnavailable)

  if (!mapFile.exists() || !mapFile.canRead()) {
    LaunchedEffect(mapFile) { currentOnUnavailable() }
    return
  }

  val current = map.points.firstOrNull { it.since != null }
  val currentDesc = current?.let { " ตอนนี้อยู่ที่ ${it.label} ${it.since}" } ?: ""
  val description = "แผนที่ย่อ " + map.points.joinToString(" · ") { "${it.number} ${it.label}" } + currentDesc

  var mapViewRef by remember { mutableStateOf<MapView?>(null) }
  var tileCacheRef by remember { mutableStateOf<TileCache?>(null) }

  DisposableEffect(mapFile.path) {
    onDispose {
      mapViewRef?.destroyAll()
      tileCacheRef?.destroy()
      AndroidGraphicFactory.clearResourceMemoryCache()
      mapViewRef = null
      tileCacheRef = null
    }
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .aspectRatio(TimelineMap.ASPECT.toFloat())
      .clip(HakuRadius.Card)
      .background(HakuColors.Track)
      .semantics { contentDescription = description },
  ) {
    key(mapFile.path) {
      val overlayLayers = remember { mutableListOf<Layer>() }
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          try {
            AndroidGraphicFactory.createInstance(ctx.applicationContext as Application)
          } catch (_: Exception) {}

          val mapView = MapView(ctx)
          mapView.isClickable = false
          try {
            mapView.mapZoomControls.isShowMapZoomControls = false
          } catch (_: Throwable) {}
          mapView.setOnTouchListener { _, _ -> false }

          val mapDataStore = try {
            MapFile(mapFile)
          } catch (_: Exception) {
            mapView.post { currentOnUnavailable() }
            return@AndroidView mapView
          }

          val cache = AndroidUtil.createTileCache(
            ctx,
            "offline-day-map",
            mapView.model.displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor,
          )
          tileCacheRef = cache

          val layer = TileRendererLayer(
            cache,
            mapDataStore,
            mapView.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE,
          )

          val theme = try {
            val themeFile = File(ctx.cacheDir, "haku-card.xml")
            ctx.assets.open("map/haku-card.xml").use { input ->
              themeFile.outputStream().use { output -> input.copyTo(output) }
            }
            ExternalRenderTheme(themeFile)
          } catch (_: Exception) {
            MapsforgeThemes.DEFAULT
          }
          layer.setXmlRenderTheme(theme)
          mapView.layerManager.layers.add(layer)

          mapViewRef = mapView
          mapView
        },
        update = { mapView ->
          updateMapContent(mapView, map, overlayLayers)
        },
      )
    }

    Text(
      text = "© OpenStreetMap contributors",
      style = MaterialTheme.typography.labelSmall,
      color = HakuColors.Ink,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(6.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(Color.White.copy(alpha = 0.75f))
        .padding(horizontal = 4.dp, vertical = 2.dp),
    )
  }
}

private fun updateMapContent(
  mapView: MapView,
  map: TimelineMapState,
  overlayLayers: MutableList<Layer>,
) {
  if (mapView.width <= 0 || mapView.height <= 0) {
    mapView.post { updateMapContent(mapView, map, overlayLayers) }
    return
  }

  overlayLayers.forEach { mapView.layerManager.layers.remove(it) }
  overlayLayers.clear()

  if (map.points.isEmpty()) {
    mapView.layerManager.redrawLayers()
    return
  }

  val density = mapView.resources.displayMetrics.density
  val points = map.points.map { it.lat to it.lon }
  val tileSize = mapView.model.displayModel.tileSize
  val paddingPx = (24 * density).toInt()

  val fit = MapFit.of(points, mapView.width, mapView.height, tileSize, paddingPx)
  if (fit != null) {
    mapView.setCenter(LatLong(fit.lat, fit.lon))
    mapView.setZoomLevel(fit.zoom.toByte())
  }

  if (map.points.size >= 2) {
    val linePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
      color = AndroidGraphicFactory.INSTANCE.createColor(0xFF7C5CF0.toInt())
      strokeWidth = 3f * density
      setStyle(Style.STROKE)
    }
    val polyline = Polyline(linePaint, AndroidGraphicFactory.INSTANCE)
    for (p in map.points) {
      polyline.latLongs.add(LatLong(p.lat, p.lon))
    }
    overlayLayers.add(polyline)
    mapView.layerManager.layers.add(polyline)
  }

  for (point in map.points) {
    val markerBitmap = createMarkerBitmap(point, density)
    val marker = Marker(
      LatLong(point.lat, point.lon),
      markerBitmap,
      -markerBitmap.width / 2,
      -markerBitmap.height / 2,
    )
    overlayLayers.add(marker)
    mapView.layerManager.layers.add(marker)
  }

  mapView.layerManager.redrawLayers()
}

private fun createMarkerBitmap(point: TimelineMapPoint, density: Float): MfBitmap {
  val r = 10f * density
  val sizePx = ceil((10f + 7f) * 2f * density).toInt()
  val bitmap = AndroidBitmap.createBitmap(sizePx, sizePx, AndroidBitmap.Config.ARGB_8888)
  val canvas = AndroidCanvas(bitmap)
  val cx = sizePx / 2f
  val cy = sizePx / 2f
  val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

  if (point.since != null) {
    paint.style = AndroidPaint.Style.STROKE
    paint.color = 0xFF2E9E6E.toInt()
    paint.strokeWidth = 2f * density
    canvas.drawCircle(cx, cy, r + 4.5f * density, paint)
  }

  paint.style = AndroidPaint.Style.FILL
  paint.color = AndroidColor.WHITE
  canvas.drawCircle(cx, cy, r + 2f * density, paint)

  paint.color = StopColors[(point.number - 1).mod(StopColors.size)]
  canvas.drawCircle(cx, cy, r, paint)

  val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
    color = AndroidColor.WHITE
    textSize = 11f * density
    textAlign = AndroidPaint.Align.CENTER
    typeface = Typeface.DEFAULT_BOLD
  }
  val text = point.number.toString()
  val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
  canvas.drawText(text, cx, textY, textPaint)

  return AndroidGraphicFactory.convertToBitmap(bitmap)
}
