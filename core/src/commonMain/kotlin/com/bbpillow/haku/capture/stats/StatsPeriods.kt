package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.memory.MemoryFormat

/** Local calendar arithmetic for week/month/year periods. Days are `yyyy-MM-dd`; epoch-day math (Hinnant) keeps it dependency-free. */
object StatsPeriods {
  const val DAY_MS = 86_400_000L
  private const val MIN_MS = 60_000L
  private const val MONDAY_OFFSET = 3L // 1970-01-01 was a Thursday
  private const val DAYS_IN_WEEK = 7

  fun of(kind: PeriodKind, day: String, tzOffsetMinutes: Int): StatsPeriod {
    val (y, m, _) = parse(day)
    val today = epochDay(day)
    val (start, end) = when (kind) {
      PeriodKind.WEEK -> (today - (today + MONDAY_OFFSET).mod(DAYS_IN_WEEK.toLong())).let { it to it + DAYS_IN_WEEK }
      PeriodKind.MONTH -> civilToDays(y, m, 1) to civilToDays(if (m == 12) y + 1 else y, if (m == 12) 1 else m + 1, 1)
      PeriodKind.YEAR -> civilToDays(y, 1, 1) to civilToDays(y + 1, 1, 1)
    }
    val local = { epochDay: Long -> epochDay * DAY_MS - tzOffsetMinutes * MIN_MS }
    return StatsPeriod(kind, dayOf(start), dayOf(end), local(start), local(end), (end - start).toInt())
  }

  /** The period right before [p] of the same kind (previous Monday-week, previous calendar month or year). */
  fun previous(p: StatsPeriod, tzOffsetMinutes: Int): StatsPeriod = of(p.kind, dayOf(epochDay(p.startDay) - 1), tzOffsetMinutes)

  /** Local day of a UTC instant. */
  fun localDay(utcMs: Long, tzOffsetMinutes: Int): Long = (utcMs + tzOffsetMinutes * MIN_MS).floorDiv(DAY_MS)

  /** Monday = 0. */
  fun weekdayOf(epochDay: Long): Int = (epochDay + MONDAY_OFFSET).mod(DAYS_IN_WEEK.toLong()).toInt()

  fun epochDay(day: String): Long = parse(day).let { (y, m, d) -> civilToDays(y, m, d) }

  fun dayOf(epochDay: Long): String = MemoryFormat.dayOf(epochDay * DAY_MS)

  private fun parse(day: String): Triple<Int, Int, Int> {
    require(Regex("""\d{4}-\d{2}-\d{2}""").matches(day)) { "bad day: $day" }
    val (y, m, d) = day.split('-').map { it.toInt() }
    require(dayOf(civilToDays(y, m, d)) == day) { "bad day: $day" } // parseUtc accepts 2026-13-40; a round trip does not
    return Triple(y, m, d)
  }

  private fun civilToDays(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = y.floorDiv(400)
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
  }
}
