package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryFormat

/**
 * A structural time filter found in a question. [toEpochMs] is exclusive. [window] = local minutes of the day the
 * question asked about ("ตอนเช้า"), null = the whole day; lines outside it are kept but rank lower.
 */
data class TimeRange(
  val word: String,
  val fromEpochMs: Long,
  val toEpochMs: Long,
  val window: IntRange? = null,
  val utcOffsetMinutes: Int = 0,
) {
  fun inWindow(atEpochMs: Long): Boolean =
    window == null || ((atEpochMs + utcOffsetMinutes * 60_000L).mod(86_400_000L) / 60_000L).toInt() in window
}

/** Thai date words -> UTC ms range in the user's local days (D18 tier 1, no model). */
object TimeWords {
  private const val DAY = 86_400_000L

  /** Monday = 0. "อาทิตย์" alone means "week" (อาทิตย์นี้), so Sunday only counts with "วัน" in front. */
  private val weekdays = listOf("จันทร์" to 0, "อังคาร" to 1, "พุธ" to 2, "พฤหัสบดี" to 3, "พฤหัส" to 3, "ศุกร์" to 4, "เสาร์" to 5, "อาทิตย์" to 6)

  // (word, today's weekday -> first..last day offset). Order = match priority; longer words before the words inside them.
  private val words: List<Pair<String, (Int) -> IntRange>> = buildList {
    add("เมื่อวานซืน" to { _ -> -2..-2 })
    add("เมื่อวาน" to { _ -> -1..-1 })
    add("วันนี้" to { _ -> 0..0 })
    for ((name, wd) in weekdays) {
      // "เสาร์ที่แล้ว" = the last Saturday before today (a week back when today is Saturday)
      val last = { today: Int -> (-((today - wd).mod(7)).let { if (it == 0) 7 else it }).let { it..it } }
      add("วัน${name}ที่แล้ว" to last)
      if (wd != 6) add("${name}ที่แล้ว" to last)
    }
    for ((name, wd) in weekdays) {
      // ponytail: a bare weekday is the most recent one, today included (the diary has no future); "วันเสาร์ที่แล้ว" reaches back further
      add("วัน$name" to { today: Int -> (-((today - wd).mod(7))).let { it..it } })
    }
    add("สัปดาห์นี้" to { _ -> -6..0 })
    add("อาทิตย์นี้" to { _ -> -6..0 })
    add("สัปดาห์ที่แล้ว" to { _ -> -13..-7 })
    add("อาทิตย์ที่แล้ว" to { _ -> -13..-7 })
  }

  // part of the day -> local minutes. Overlaps are fine: they only change ranking, never what is loaded.
  private val MORNING = 300 until 720
  private val AFTERNOON = 720 until 1020
  private val EVENING = 960 until 1200
  private val NIGHT = 1140 until 1440 // ponytail: after-midnight hours are the next day's file; wrap the window if "ดึก" questions miss
  private val parts = listOf(
    "ตอนเช้า" to MORNING, "ช่วงเช้า" to MORNING, "เช้านี้" to MORNING,
    "ตอนบ่าย" to AFTERNOON, "ช่วงบ่าย" to AFTERNOON, "บ่ายนี้" to AFTERNOON,
    "ตอนเย็น" to EVENING, "ช่วงเย็น" to EVENING, "เย็นนี้" to EVENING,
    "ตอนกลางคืน" to NIGHT, "ช่วงกลางคืน" to NIGHT, "กลางคืน" to NIGHT, "คืนนี้" to NIGHT,
  )

  /** A day word picks the days; a part-of-day word alone means today. */
  fun find(normalizedQuestion: String, nowEpochMs: Long, utcOffsetMinutes: Int): TimeRange? {
    val day = words.firstOrNull { it.first in normalizedQuestion }
    val part = parts.firstOrNull { it.first in normalizedQuestion }
    if (day == null && part == null) return null
    val offset = utcOffsetMinutes * 60_000L
    val localDay = (nowEpochMs + offset).floorDiv(DAY)
    val todayStart = localDay * DAY - offset
    val span = day?.second?.invoke((localDay + 3).mod(7L).toInt()) ?: 0..0 // epoch day 0 was a Thursday
    val word = listOfNotNull(day?.first, part?.first).joinToString(" ")
    return TimeRange(word, todayStart + span.first * DAY, todayStart + (span.last + 1) * DAY, part?.second, utcOffsetMinutes)
  }

  /** Every UTC day (yyyy-MM-dd) whose file may hold records inside [range]. */
  fun utcDays(range: TimeRange): List<String> {
    val firstDay = range.fromEpochMs.floorDiv(DAY)
    val lastDay = (range.toEpochMs - 1).floorDiv(DAY)
    return (firstDay..lastDay).map { MemoryFormat.dayOf(it * DAY) }
  }

  /** Every time phrase, longest first, so a question can be stripped of them before word search. */
  val all: List<String> = (words.map { it.first } + parts.map { it.first }).distinct().sortedByDescending { it.length }
}
