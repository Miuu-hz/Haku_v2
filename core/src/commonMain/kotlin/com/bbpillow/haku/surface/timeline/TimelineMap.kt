package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.OpenStay
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionOutput
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.diary.DiaryTemplates
import kotlin.math.PI
import kotlin.math.cos

/**
 * One stop on the day's mini map. [x]/[y] are 0..1 of the card (y down, north up), already fitted with uniform scale
 * for a card of [TimelineMap.ASPECT]. [number] is the same number the matching timeline rows show.
 */
data class TimelineMapPoint(
  val number: Int,
  val x: Double,
  val y: Double,
  val label: String,
  val category: PlaceCategory,
  /** Non-null marks where the user is right now, worded for display ("ตั้งแต่ 19:12"). This stop has no timeline row yet. */
  val since: String? = null,
)

data class TimelineMapState(val points: List<TimelineMapPoint>)

/** Why the day has no map card to draw. */
enum class TimelineMapEmpty { CAPTURE_OFF, NO_STAYS }

/**
 * The day's mini map (P3.2), tile-free: coordinates never leave the device and no map SDK is involved, so all we have
 * is the stays themselves, projected equirectangularly onto a small card. Only stays that still have a live timeline
 * row count (a line the user deleted takes its stop off the map; a deleted capture event is never recognised at all).
 */
object TimelineMap {
  /** Card width / height. The UI draws at exactly this ratio so x and y share one scale. */
  const val ASPECT = 2.4

  /** Empty margin on every side, in card heights, so dots and their numbers are not clipped. */
  const val PADDING = 0.16

  /**
   * Stops for the rows at [lineTimesUtcMs], in visit order, plus the number each row time gets. A stay matches a row
   * when the row sits at its arrival or departure time (the diary writes lines at exactly those times, and an edit
   * keeps the time). Null when fewer than two distinct places remain: one dot is not a map.
   */
  fun build(
    lineTimesUtcMs: Collection<Long>,
    recognized: PlaceRecognitionOutput,
    current: OpenStay? = null,
    currentSince: String? = null,
  ): Pair<TimelineMapState, Map<Long, Int>>? {
    val times = lineTimesUtcMs.toSet()
    val byId = recognized.places.associateBy { it.id }
    val stops = recognized.visits.sortedBy { it.startUtcMs }
      .filter { it.startUtcMs in times || it.endUtcMs in times }
      .mapNotNull { v -> byId[v.placeId]?.let { v to it } }
    if (stops.map { (_, p) -> p.lat to p.lon }.distinct().size < 2) return null
    val xy = project(stops.map { (_, p) -> p.lat to p.lon })
    val points = stops.mapIndexed { i, (_, p) -> TimelineMapPoint(i + 1, xy[i].first, xy[i].second, labelOf(p), categoryOf(p)) }
    val numberAt = buildMap {
      stops.forEachIndexed { i, (v, _) ->
        if (v.startUtcMs in times) put(v.startUtcMs, i + 1)
        if (v.endUtcMs in times) put(v.endUtcMs, i + 1)
      }
    }
    return TimelineMapState(points) to numberAt
  }

  /**
   * Equirectangular projection fitted to the bounding box: longitude shrunk by cos(mid latitude) so a block is square,
   * one scale for both axes, centred inside [PADDING]. Returns (x, y) in 0..1, y = 0 at the north edge.
   * ponytail: no antimeridian handling; a day crossing ±180° lon draws the long way round.
   */
  internal fun project(latLon: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    val lats = latLon.map { it.first }
    val k = cos((lats.min() + lats.max()) / 2 * PI / 180)
    val xs = latLon.map { it.second * k }
    val minX = xs.min()
    val maxY = lats.max()
    val spanX = xs.max() - minX
    val spanY = maxY - lats.min()
    val scale = minOf(
      if (spanX > 0) (ASPECT - 2 * PADDING) / spanX else Double.MAX_VALUE,
      if (spanY > 0) (1 - 2 * PADDING) / spanY else Double.MAX_VALUE,
    ).takeIf { it < Double.MAX_VALUE } ?: 0.0 // all points on one spot: everything lands in the centre
    val offX = (ASPECT - spanX * scale) / 2
    val offY = (1 - spanY * scale) / 2
    return latLon.indices.map { i -> (offX + (xs[i] - minX) * scale) / ASPECT to offY + (maxY - lats[i]) * scale }
  }

  // Same rule as the diary: an unconfirmed place shows Haku's own name (PlaceNames) and never a home/work icon.
  private fun labelOf(p: PlaceRecord): String = when {
    !p.confirmed -> p.autoName ?: DiaryTemplates.UNKNOWN_PLACE
    !p.label.isNullOrBlank() -> p.label
    p.category == PlaceCategory.HOME -> DiaryTemplates.HOME
    p.category == PlaceCategory.WORK -> DiaryTemplates.WORK
    else -> DiaryTemplates.UNKNOWN_PLACE
  }

  private fun categoryOf(p: PlaceRecord): PlaceCategory = if (p.confirmed) p.category else PlaceCategory.OTHER
}
