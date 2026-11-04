package com.bbpillow.haku.surface.timeline

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * One pre-cut map tile cell.
 *
 * Privacy rule: the download request must name a fixed, pre-cut cell, never a box computed
 * around the person, so the server learns only a coarse area. The result must never be logged
 * or stored as text outside the device.
 */
data class MapCell(
  val id: String,
  val south: Double,
  val west: Double,
  val north: Double,
  val east: Double,
) {
  fun contains(lat: Double, lon: Double): Boolean =
    lat in south..north && lon in west..east
}

object MapArea {
  /** Half a cell's side and the step between cell centres, in degrees. Claude tunes these from measured file sizes. */
  const val HALF_SIDE_DEG = 0.9
  const val STEP_DEG = 0.8

  /** Where the user usually is: the median of stay positions weighted by stay length, so one far trip barely moves it. Null with no stays. */
  fun usualLocation(stays: List<Stay>): Pair<Double, Double>? {
    val valid = stays.filter { it.durationMs > 0 }
    if (valid.isEmpty()) return null
    val totalWeight = valid.sumOf { it.durationMs }
    val medianLat = weightedMedian(valid.map { it.lat to it.durationMs }, totalWeight)
    val medianLon = weightedMedian(valid.map { it.lon to it.durationMs }, totalWeight)
    return medianLat to medianLon
  }

  /** The cell whose centre is nearest. Centres are at STEP_DEG multiples of latitude and longitude. */
  fun cellFor(lat: Double, lon: Double): MapCell {
    val latStep = round(lat / STEP_DEG).toInt()
    val lonStep = round(lon / STEP_DEG).toInt()
    return cellForSteps(latStep, lonStep)
  }

  /** All cells that overlap the box, for making the files. */
  fun cellsCovering(south: Double, west: Double, north: Double, east: Double): List<MapCell> {
    if (south > north || west > east) return emptyList()
    // ponytail: search bounds expanded by one cell half-side so no overlapping cell is missed
    val minLatStep = floor((south - HALF_SIDE_DEG) / STEP_DEG).toInt()
    val maxLatStep = ceil((north + HALF_SIDE_DEG) / STEP_DEG).toInt()
    val minLonStep = floor((west - HALF_SIDE_DEG) / STEP_DEG).toInt()
    val maxLonStep = ceil((east + HALF_SIDE_DEG) / STEP_DEG).toInt()

    val cells = mutableListOf<MapCell>()
    for (latStep in minLatStep..maxLatStep) {
      for (lonStep in minLonStep..maxLonStep) {
        val cell = cellForSteps(latStep, lonStep)
        if (cell.overlaps(south, west, north, east)) {
          cells.add(cell)
        }
      }
    }
    return cells
  }

  data class Stay(val lat: Double, val lon: Double, val durationMs: Long)

  private fun cellForSteps(latStep: Int, lonStep: Int): MapCell {
    val centerLat = latStep * STEP_DEG
    val centerLon = lonStep * STEP_DEG
    return MapCell(
      id = cellId(latStep, lonStep),
      south = centerLat - HALF_SIDE_DEG,
      west = centerLon - HALF_SIDE_DEG,
      north = centerLat + HALF_SIDE_DEG,
      east = centerLon + HALF_SIDE_DEG,
    )
  }

  private fun cellId(latStep: Int, lonStep: Int): String =
    "${formatCoord(latStep, 'n', 's')}_${formatCoord(lonStep, 'e', 'w')}"

  private fun formatCoord(step: Int, pos: Char, neg: Char): String {
    val hundredths = round(abs(step * STEP_DEG) * 100).toLong()
    val deg = hundredths / 100
    val cent = hundredths % 100
    val prefix = if (step >= 0) pos else neg
    val centStr = if (cent < 10) "0$cent" else cent.toString()
    return "$prefix${deg}_$centStr"
  }

  private fun weightedMedian(coords: List<Pair<Double, Long>>, totalWeight: Long): Double {
    val sorted = coords.sortedBy { it.first }
    val half = totalWeight / 2.0
    var acc = 0L
    for (i in sorted.indices) {
      acc += sorted[i].second
      if (acc > half) return sorted[i].first
      if (acc.toDouble() == half) {
        return if (i + 1 < sorted.size) (sorted[i].first + sorted[i + 1].first) / 2.0 else sorted[i].first
      }
    }
    return sorted.last().first
  }

  private fun MapCell.overlaps(s: Double, w: Double, n: Double, e: Double): Boolean =
    south <= n && north >= s && west <= e && east >= w
}
