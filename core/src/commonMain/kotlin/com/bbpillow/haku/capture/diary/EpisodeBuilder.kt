package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord

/**
 * Pure rules: one day's recognized stays + calendar entries in, ordered episodes out.
 * Stays shorter than the place recognizer's minimum never arrive here, so "7 min is not an event" is decided once, upstream.
 */
object EpisodeBuilder {
  fun build(input: DiaryBuildInput): List<DiaryEpisode> {
    val day = input.dayStartUtcMs until input.dayEndUtcMs
    val visits = input.visits.sortedBy { it.startUtcMs }
    val starts = input.events.filter { it.source == CaptureSource.DwellStart }.groupBy({ it.timeUtcMs }, { it.id })
    val ends = input.events.filter { it.source == CaptureSource.DwellEnd }.groupBy({ it.timeUtcMs }, { it.id })
    val episodes = mutableListOf<DiaryEpisode>()

    visits.forEachIndexed { i, v ->
      val ref = placeRef(input.places, v)
      if (v.startUtcMs in day) {
        val gap = visits.getOrNull(i - 1)?.let { v.startUtcMs - it.endUtcMs }
        val travel = gap?.takeIf { it in DiaryThresholds.MIN_TRAVEL_MS..DiaryThresholds.MAX_TRAVEL_MS }
        val stay = (v.endUtcMs - v.startUtcMs).takeIf { v.endUtcMs <= input.dayEndUtcMs }
        episodes += DiaryEpisode.Arrival(v.startUtcMs, ref, travel, stay, starts[v.startUtcMs].orEmpty() + ends[v.endUtcMs].orEmpty())
      }
      if (v.endUtcMs in day) {
        val walkToNext = visits.getOrNull(i + 1)?.let { it.startUtcMs - v.endUtcMs < DiaryThresholds.MIN_TRAVEL_MS } ?: false
        if (!walkToNext) episodes += DiaryEpisode.Departure(v.endUtcMs, ref, ends[v.endUtcMs].orEmpty())
      }
    }
    input.events.filter { it.timeUtcMs in day }.forEach { e -> calendar(e)?.let(episodes::add) }
    return cap(episodes).sortedBy { it.timeUtcMs }
  }

  private fun calendar(e: CaptureEvent): DiaryEpisode? =
    (e.source as? CaptureSource.CalendarEntry)?.let { DiaryEpisode.Calendar(e.timeUtcMs, it.title, e.endUtcMs, listOf(e.id)) }

  // Nothing is shown for a place the user has not confirmed (D14: never invent names).
  private fun placeRef(places: List<PlaceRecord>, v: PlaceVisitRecord): PlaceRef =
    places.firstOrNull { it.id == v.placeId && it.confirmed }?.let { PlaceRef(it.label, it.category, v.placeId) } ?: PlaceRef(placeId = v.placeId)

  // Over the cap, drop lowest priority first, latest first within a priority (mornings matter most to a diary).
  private fun cap(all: List<DiaryEpisode>): List<DiaryEpisode> {
    if (all.size <= DiaryThresholds.MAX_LINES) return all
    val drop = all.sortedWith(compareBy({ it.priority }, { -it.timeUtcMs })).take(all.size - DiaryThresholds.MAX_LINES).toSet()
    return all.filterNot { it in drop }
  }
}
