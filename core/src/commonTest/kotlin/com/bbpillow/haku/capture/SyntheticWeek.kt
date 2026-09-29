package com.bbpillow.haku.capture

/**
 * Deterministic week of dwell events for one person in Bangkok (UTC+7), Mon 2026-09-07 to Mon 08:15.
 * home: nights and weekend; office: weekdays (with a 5 min walk splitting the morning stay);
 * cafe: 40 min weekday lunch + 2 h Saturday; gym: Mon and Wed evening; store: one 7 min stop (not an event).
 * GPS jitter is a fixed LCG, about +-20 m.
 */
object SyntheticWeek {
  const val TZ_OFFSET_MIN = 420
  const val MIN_MS = 60_000L
  private const val DAY_MS = 86_400_000L
  private const val WEEK_START_LOCAL_EPOCH_DAY = 20_703L // Monday 2026-09-07

  val HOME = 13.7563 to 100.5018
  val OFFICE = 13.8013 to 100.5018 // ~5 km north
  val CAFE = 13.7563 to 100.5108 // ~1 km east
  val GYM = 13.7383 to 100.5018 // ~2 km south
  val STORE = 13.7593 to 100.5018 // ~330 m from home

  /** UTC ms of local [day] (0 = Monday) at [hour]:[minute]. */
  fun at(day: Int, hour: Int, minute: Int = 0): Long =
    (WEEK_START_LOCAL_EPOCH_DAY + day) * DAY_MS + (hour * 60L + minute) * MIN_MS - TZ_OFFSET_MIN * MIN_MS

  private data class Stay(val at: Pair<Double, Double>, val start: Long, val end: Long)

  private fun stays(): List<Stay> = buildList {
    for (d in 0..4) {
      add(Stay(HOME, at(d - 1, 20), at(d, 8, 15)))
      add(Stay(OFFICE, at(d, 9), at(d, 10, 30)))
      add(Stay(OFFICE, at(d, 10, 35), at(d, 12, 30)))
      add(Stay(CAFE, at(d, 12, 40), at(d, 13, 20)))
      add(Stay(OFFICE, at(d, 13, 30), at(d, 18)))
      if (d == 0 || d == 2) add(Stay(GYM, at(d, 18, 30), at(d, 19, 30)))
      if (d == 1) add(Stay(STORE, at(d, 18, 10), at(d, 18, 17)))
    }
    add(Stay(HOME, at(4, 20), at(5, 9, 45)))
    add(Stay(CAFE, at(5, 10), at(5, 12)))
    add(Stay(HOME, at(5, 12, 30), at(7, 8, 15)))
  }

  fun events(): List<CaptureEvent> {
    var seed = 42L
    fun jitter(): Double {
      seed = (seed * 1_103_515_245L + 12_345L) and 0x7fffffffL
      return ((seed % 2001) - 1000) / 1000.0 * 0.0002 // +-0.0002 deg ~ 22 m
    }
    return stays().flatMap { s ->
      val place = EventPlace(s.at.first + jitter(), s.at.second + jitter())
      listOf(
        CaptureEvent("dwell-start-${s.start}", s.start, CaptureSource.DwellStart, place),
        CaptureEvent("dwell-end-${s.end}", s.end, CaptureSource.DwellEnd, place),
      )
    }
  }
}
