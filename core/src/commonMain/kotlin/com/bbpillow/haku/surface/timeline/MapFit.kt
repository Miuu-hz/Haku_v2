package com.bbpillow.haku.surface.timeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

/** Zoom and centre that show every stop on a card; pure so it can be tested. */
data class MapFitOutput(val lat: Double, val lon: Double, val zoom: Int)

object MapFit {
  const val MIN_ZOOM = 10
  const val MAX_ZOOM = 16

  /** Null for no points. One point, or points on one spot, gets MAX_ZOOM. */
  fun of(
    points: List<Pair<Double, Double>>,
    widthPx: Int,
    heightPx: Int,
    tileSizePx: Int,
    paddingPx: Int,
  ): MapFitOutput? {
    if (points.isEmpty()) return null

    val lats = points.map { it.first }
    val lons = points.map { it.second }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()

    val centerLat = (minLat + maxLat) / 2.0
    val centerLon = (minLon + maxLon) / 2.0

    if (minLat == maxLat && minLon == maxLon) {
      return MapFitOutput(centerLat, centerLon, MAX_ZOOM)
    }

    val availW = widthPx - 2 * paddingPx
    val availH = heightPx - 2 * paddingPx
    if (availW <= 0 || availH <= 0) {
      return MapFitOutput(centerLat, centerLon, MIN_ZOOM)
    }

    val cX = xNorm(centerLon)
    val cY = yNorm(centerLat)

    for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
      val worldSize = tileSizePx.toDouble() * (1 shl zoom)
      val fits = points.all { (lat, lon) ->
        val dx = abs(xNorm(lon) - cX) * worldSize
        val dy = abs(yNorm(lat) - cY) * worldSize
        dx <= availW / 2.0 && dy <= availH / 2.0
      }
      if (fits) {
        return MapFitOutput(centerLat, centerLon, zoom)
      }
    }

    return MapFitOutput(centerLat, centerLon, MIN_ZOOM)
  }

  private fun xNorm(lon: Double): Double = (lon + 180.0) / 360.0

  private fun yNorm(lat: Double): Double {
    val sinLat = sin(lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
    return 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)
  }
}
