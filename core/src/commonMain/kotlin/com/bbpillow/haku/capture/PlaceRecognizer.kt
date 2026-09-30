package com.bbpillow.haku.capture

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Thresholds for place recognition. Starting values, tune with real weeks of data (P2.2 field test). */
object PlaceThresholds {
  // ponytail: 15 min matches v1's dwell tracker; the plan's "7 min is not an event, 2 h is" sits well either side.
  const val MIN_DWELL_MS = 15 * 60_000L

  // ponytail: GPS jitter and a walk to the coffee machine split one stay in two; glue stays <=10 min apart within 150 m
  // (150 m = v1 dwell radius). A real errand (>10 min away) stays separate.
  const val MERGE_GAP_MS = 10 * 60_000L
  const val MERGE_RADIUS_M = 150.0

  // ponytail: two stays within 100 m are "the same place" (v1 geofence default). Greedy first-come clustering,
  // so order matters slightly; switch to DBSCAN only if dense city blocks merge wrongly.
  const val PLACE_RADIUS_M = 100.0

  // Home = where you spend nights; Work = where you spend weekday daytime. Night 22:00-06:00, day 09:00-17:00.
  const val NIGHT_START_HOUR = 22
  const val NIGHT_LENGTH_HOURS = 8
  const val WORK_START_HOUR = 9
  const val WORK_LENGTH_HOURS = 8

  // A day counts toward a pattern when the place covers >=2 h of its window; need 3 such days before guessing.
  const val MIN_WINDOW_OVERLAP_MS = 2 * 3_600_000L
  const val MIN_PATTERN_DAYS = 3
}

/** Pure functions: dwell events in, places/visits/guesses out. No I/O. */
object PlaceRecognizer {
  private const val DAY_MS = 86_400_000L
  private const val HOUR_MS = 3_600_000L
  private const val EARTH_RADIUS_M = 6_371_000.0

  fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * PI / 180
    val dLon = (lon2 - lon1) * PI / 180
    val a = sin(dLat / 2).let { it * it } +
      cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
  }

  /** Pair each DwellStart (with a position) with the next DwellEnd. An unmatched start is still ongoing and is skipped. */
  fun pairDwells(events: List<CaptureEvent>): List<DwellRecord> {
    val out = mutableListOf<DwellRecord>()
    var open: CaptureEvent? = null
    for (e in events.sortedWith(compareBy({ it.timeUtcMs }, { it.id }))) {
      when (e.source) {
        CaptureSource.DwellStart -> open = e.takeIf { it.place != null }
        CaptureSource.DwellEnd -> open?.let { s ->
          if (e.timeUtcMs > s.timeUtcMs) out += DwellRecord(s.timeUtcMs, e.timeUtcMs, s.place!!.lat, s.place.lon)
          open = null
        }
        else -> Unit
      }
    }
    return out
  }

  /** Glue jitter-split stays together, then drop stays shorter than [PlaceThresholds.MIN_DWELL_MS]. */
  fun mergeStops(dwells: List<DwellRecord>): List<DwellRecord> {
    val merged = mutableListOf<DwellRecord>()
    for (d in dwells.sortedBy { it.startUtcMs }) {
      val last = merged.lastOrNull()
      val close = last != null &&
        d.startUtcMs - last.endUtcMs <= PlaceThresholds.MERGE_GAP_MS &&
        distanceMeters(last.lat, last.lon, d.lat, d.lon) <= PlaceThresholds.MERGE_RADIUS_M
      if (close) {
        val w1 = last!!.durationMs.toDouble()
        val w2 = d.durationMs.toDouble()
        val total = (w1 + w2).coerceAtLeast(1.0)
        merged[merged.lastIndex] = DwellRecord(
          last.startUtcMs,
          maxOf(last.endUtcMs, d.endUtcMs),
          (last.lat * w1 + d.lat * w2) / total,
          (last.lon * w1 + d.lon * w2) / total,
        )
      } else {
        merged += d
      }
    }
    return merged.filter { it.durationMs >= PlaceThresholds.MIN_DWELL_MS }
  }

  /** Attach each stay to the nearest known place within radius, else start a new place at that stay. */
  fun assignPlaces(stops: List<DwellRecord>, known: List<PlaceRecord>): Pair<List<PlaceRecord>, List<PlaceVisitRecord>> {
    val places = known.toMutableList()
    val counts = HashMap<String, Int>()
    val visits = mutableListOf<PlaceVisitRecord>()
    for (s in stops) {
      val nearest = places
        .map { it to distanceMeters(it.lat, it.lon, s.lat, s.lon) }
        .filter { it.second <= PlaceThresholds.PLACE_RADIUS_M }
        .minByOrNull { it.second }?.first
      val target = if (nearest == null) {
        PlaceRecord("place-${s.startUtcMs}", s.lat, s.lon).also { places += it; counts[it.id] = 1 }
      } else if (nearest.confirmed || nearest.id !in counts) {
        nearest // confirmed or pre-existing places keep their coordinates
      } else {
        // unconfirmed place made this run: running mean of stay coordinates
        val n = counts.getValue(nearest.id)
        val moved = nearest.copy(
          lat = (nearest.lat * n + s.lat) / (n + 1),
          lon = (nearest.lon * n + s.lon) / (n + 1),
        )
        places[places.indexOfFirst { it.id == nearest.id }] = moved
        counts[nearest.id] = n + 1
        moved
      }
      visits += PlaceVisitRecord(target.id, s.startUtcMs, s.endUtcMs)
    }
    return places to visits
  }

  /** Guess home and work among unconfirmed places from when the user is there. Confirmed categories are never re-asked. */
  fun guessHomeWork(places: List<PlaceRecord>, visits: List<PlaceVisitRecord>, tzOffsetMinutes: Int): List<PlaceGuessOutput> {
    val byPlace = visits.groupBy { it.placeId }
    val offsetMs = tzOffsetMinutes * 60_000L
    fun stats(id: String, startHour: Int, lengthHours: Int, weekdaysOnly: Boolean) =
      windowDays(byPlace[id].orEmpty(), offsetMs, startHour, lengthHours, weekdaysOnly)

    val candidates = places.filter { !it.confirmed }
    val guesses = mutableListOf<PlaceGuessOutput>()
    val homeKnown = places.any { it.confirmed && it.category == PlaceCategory.HOME }
    val workKnown = places.any { it.confirmed && it.category == PlaceCategory.WORK }

    var homeId: String? = null
    if (!homeKnown) {
      best(candidates) { stats(it.id, PlaceThresholds.NIGHT_START_HOUR, PlaceThresholds.NIGHT_LENGTH_HOURS, false) }?.let { (p, days) ->
        homeId = p.id
        guesses += PlaceGuessOutput(p.id, PlaceCategory.HOME, days, p.lat, p.lon)
      }
    }
    if (!workKnown) {
      best(candidates.filter { it.id != homeId }) {
        stats(it.id, PlaceThresholds.WORK_START_HOUR, PlaceThresholds.WORK_LENGTH_HOURS, true)
      }?.let { (p, days) -> guesses += PlaceGuessOutput(p.id, PlaceCategory.WORK, days, p.lat, p.lon) }
    }
    return guesses
  }

  fun recognize(input: PlaceRecognitionInput): PlaceRecognitionOutput {
    val (places, visits) = assignPlaces(mergeStops(input.dwells), input.knownPlaces)
    return PlaceRecognitionOutput(places, visits, guessHomeWork(places, visits, input.tzOffsetMinutes))
  }

  // Highest day-count wins (ties: more total minutes, then id); below MIN_PATTERN_DAYS nobody wins.
  private fun best(candidates: List<PlaceRecord>, score: (PlaceRecord) -> Pair<Int, Long>): Pair<PlaceRecord, Int>? =
    candidates
      .map { c -> score(c).let { Triple(c, it.first, it.second) } }
      .filter { it.second >= PlaceThresholds.MIN_PATTERN_DAYS }
      .sortedWith(compareBy({ -it.second }, { -it.third }, { it.first.id }))
      .firstOrNull()?.let { it.first to it.second }

  /** (days on which the visits cover >= MIN_WINDOW_OVERLAP of the daily window, total overlap ms). */
  private fun windowDays(
    visits: List<PlaceVisitRecord>,
    offsetMs: Long,
    startHour: Int,
    lengthHours: Int,
    weekdaysOnly: Boolean,
  ): Pair<Int, Long> {
    val overlapByDay = HashMap<Long, Long>()
    for (v in visits) {
      val firstDay = (v.startUtcMs + offsetMs).floorDiv(DAY_MS) - 1
      val lastDay = (v.endUtcMs + offsetMs).floorDiv(DAY_MS)
      for (day in firstDay..lastDay) {
        // epoch day 0 (1970-01-01) was a Thursday; Monday = 0
        if (weekdaysOnly && (day + 3).mod(7L) >= 5) continue
        val winStart = day * DAY_MS + startHour * HOUR_MS - offsetMs
        val winEnd = winStart + lengthHours * HOUR_MS
        val overlap = minOf(v.endUtcMs, winEnd) - maxOf(v.startUtcMs, winStart)
        if (overlap > 0) overlapByDay[day] = (overlapByDay[day] ?: 0L) + overlap
      }
    }
    val good = overlapByDay.values.filter { it >= PlaceThresholds.MIN_WINDOW_OVERLAP_MS }
    return good.size to good.sum()
  }
}
