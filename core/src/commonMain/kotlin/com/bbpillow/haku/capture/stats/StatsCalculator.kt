package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.diary.DiaryThresholds
import com.bbpillow.haku.capture.diary.PlaceRef

/** Tunables for what counts as a pattern. Starting values; adjust with real months of data. */
object StatsThresholds {
  const val MIN_WEEKDAY_OCCURRENCES = 2 // a weekday seen once is an event, not a pattern
  const val MIN_WEEKDAY_AVG_MS = 30 * 60_000L
  const val WEEKDAY_LEAD = 1.5 // the busiest weekday must beat the runner-up by this factor
  const val AWAY_CHANGE_FRACTION = 0.2
  const val AWAY_CHANGE_MIN_MS = 15 * 60_000L
  const val PLACE_RATE_FACTOR = 1.5
  const val PLACE_MIN_DIFF = 2
}

/** Pure: stays, places and calendar events in, measured numbers and evidence-backed claims out. Never estimates; a number it cannot measure is null. */
object StatsCalculator {
  private data class Segment(val placeId: String, val localDay: Long, val ms: Long)

  fun compute(input: PeriodStatsInput): PeriodStats {
    val p = input.period
    val byId = input.places.associateBy { it.id }
    val category = { id: String -> byId[id]?.takeIf { it.confirmed }?.category }
    val segments = segments(input)
    val recorded = segments.sumOf { it.ms }
    val hasHome = input.places.any { it.confirmed && it.category == PlaceCategory.HOME }
    val hasWork = input.places.any { it.confirmed && it.category == PlaceCategory.WORK }
    val home = segments.filter { category(it.placeId) == PlaceCategory.HOME }.sumOf { it.ms }
    val work = segments.filter { category(it.placeId) == PlaceCategory.WORK }.sumOf { it.ms }
    val other = { s: Segment -> category(s.placeId).let { it != PlaceCategory.HOME && it != PlaceCategory.WORK } }
    val started = input.visits.filter { it.startUtcMs in p.fromUtcMs until p.toUtcMs }

    return PeriodStats(
      period = p,
      coveredDays = segments.map { it.localDay }.toSet().size,
      recordedMs = recorded,
      homeMs = home.takeIf { hasHome },
      workMs = work.takeIf { hasWork },
      otherMs = (recorded - home - work).takeIf { hasHome && hasWork },
      distinctPlaces = segments.map { it.placeId }.toSet().size,
      newPlaces = newPlaces(input),
      placeUses = placeUses(started, byId, segments),
      travelMs = travel(started),
      appointments = input.events.count { it.source is CaptureSource.CalendarEntry && it.timeUtcMs in p.fromUtcMs until p.toUtcMs },
      weekdays = weekdays(segments, other),
    )
  }

  /** Claims from one period alone. Empty for a week (no weekday repeats). */
  fun insights(s: PeriodStats): List<StatsInsight> {
    val perDay = s.weekdays.mapIndexedNotNull { i, w ->
      if (s.otherMs == null || w.coveredDays < StatsThresholds.MIN_WEEKDAY_OCCURRENCES) null else i to w.otherMs / w.coveredDays
    }.sortedByDescending { it.second }
    val top = perDay.firstOrNull() ?: return emptyList()
    val runnerUp = perDay.getOrNull(1)?.second ?: 0L
    val leads = top.second >= StatsThresholds.MIN_WEEKDAY_AVG_MS && top.second >= runnerUp * StatsThresholds.WEEKDAY_LEAD
    return if (leads) listOf(StatsInsight.BusiestWeekday(top.first, top.second, s.weekdays[top.first].coveredDays, runnerUp)) else emptyList()
  }

  /** Claims that need both periods. Empty unless both have enough recorded days; a comparison is never invented from one side. */
  fun compare(current: PeriodStats, previous: PeriodStats): List<StatsInsight> {
    if (!current.isReliable || !previous.isReliable) return emptyList()
    return listOfNotNull(awayChange(current, previous)) + placeChanges(current, previous)
  }

  private fun awayChange(cur: PeriodStats, prev: PeriodStats): StatsInsight? {
    val c = cur.awayFromHomeMs?.div(cur.coveredDays) ?: return null
    val p = prev.awayFromHomeMs?.div(prev.coveredDays) ?: return null
    val big = kotlin.math.abs(c - p) >= StatsThresholds.AWAY_CHANGE_MIN_MS && kotlin.math.abs(c - p) >= p * StatsThresholds.AWAY_CHANGE_FRACTION
    return if (big) StatsInsight.AwayFromHomeChanged(c, p, cur.coveredDays, prev.coveredDays) else null
  }

  // Named places only: "สถานที่หนึ่ง" twice would let two different places read as one claim.
  private fun placeChanges(cur: PeriodStats, prev: PeriodStats): List<StatsInsight> {
    val before = prev.placeUses.associateBy { it.placeId }
    return cur.placeUses.filter { it.category == PlaceCategory.OTHER && !it.ref.label.isNullOrBlank() }.mapNotNull { now ->
      val was = before[now.placeId]?.visits ?: 0
      val rateNow = now.visits.toDouble() / cur.coveredDays
      val ratePrev = was.toDouble() / prev.coveredDays
      val more = now.visits - was >= StatsThresholds.PLACE_MIN_DIFF && rateNow >= ratePrev * StatsThresholds.PLACE_RATE_FACTOR
      val less = was - now.visits >= StatsThresholds.PLACE_MIN_DIFF && ratePrev >= rateNow * StatsThresholds.PLACE_RATE_FACTOR
      if (more || less) StatsInsight.PlaceVisitsChanged(now, now.visits, was, cur.coveredDays, prev.coveredDays) else null
    }.sortedByDescending { kotlin.math.abs((it as StatsInsight.PlaceVisitsChanged).current - it.previous) }
  }

  /** Stays clipped to the period and cut at local midnights, so each segment belongs to exactly one local day. */
  private fun segments(input: PeriodStatsInput): List<Segment> = buildList {
    val p = input.period
    for (v in input.visits) {
      var from = maxOf(v.startUtcMs, p.fromUtcMs)
      val to = minOf(v.endUtcMs, p.toUtcMs)
      while (from < to) {
        val day = StatsPeriods.localDay(from, input.tzOffsetMinutes)
        val cut = minOf(to, (day + 1) * StatsPeriods.DAY_MS - input.tzOffsetMinutes * 60_000L)
        add(Segment(v.placeId, day, cut - from))
        from = cut
      }
    }
  }

  private fun newPlaces(input: PeriodStatsInput): Int? {
    val firstSeen = input.visits.groupBy { it.placeId }.mapValues { (_, v) -> v.minOf { it.startUtcMs } }
    if (firstSeen.isEmpty() || firstSeen.values.min() >= input.period.fromUtcMs) return null // no history: nothing can be called new
    return firstSeen.values.count { it in input.period.fromUtcMs until input.period.toUtcMs }
  }

  private fun placeUses(started: List<com.bbpillow.haku.capture.PlaceVisitRecord>, byId: Map<String, PlaceRecord>, segments: List<Segment>): List<PlaceUse> {
    val ms = segments.groupBy { it.placeId }.mapValues { (_, s) -> s.sumOf { it.ms } }
    return started.groupBy { it.placeId }.mapNotNull { (id, v) ->
      val place = byId[id]?.takeIf { it.confirmed }
      if (place?.category == PlaceCategory.HOME) null
      else PlaceUse(id, PlaceRef(place?.label, place?.category ?: PlaceCategory.OTHER, id), place?.category ?: PlaceCategory.OTHER, v.size, ms[id] ?: 0L)
    }.sortedWith(compareByDescending<PlaceUse> { it.visits }.thenByDescending { it.totalMs }.thenBy { it.placeId })
  }

  // Only gaps the diary would call a trip (10..90 min): shorter is a walk, longer hides unrecorded stops.
  private fun travel(started: List<com.bbpillow.haku.capture.PlaceVisitRecord>): Long =
    started.sortedBy { it.startUtcMs }.zipWithNext { a, b -> b.startUtcMs - a.endUtcMs }
      .filter { it in DiaryThresholds.MIN_TRAVEL_MS..DiaryThresholds.MAX_TRAVEL_MS }.sum()

  private fun weekdays(segments: List<Segment>, isOther: (Segment) -> Boolean): List<WeekdayUse> =
    (0 until 7).map { wd ->
      val mine = segments.filter { StatsPeriods.weekdayOf(it.localDay) == wd }
      WeekdayUse(mine.map { it.localDay }.toSet().size, mine.filter(isOther).sumOf { it.ms })
    }
}
