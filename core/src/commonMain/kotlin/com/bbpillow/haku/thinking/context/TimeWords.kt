package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryFormat

/** A structural time filter found in a question. [toEpochMs] is exclusive. */
data class TimeRange(val word: String, val fromEpochMs: Long, val toEpochMs: Long)

/** Thai date words -> UTC ms range in the user's local days (D18 tier 1, no model). */
object TimeWords {
  private const val DAY = 86_400_000L

  // (word, first day offset, last day offset) relative to today. Longest words first so "เมื่อวานซืน" beats "เมื่อวาน".
  private val words = listOf(
    Triple("เมื่อวานซืน", -2, -2),
    Triple("เมื่อวาน", -1, -1),
    Triple("วันนี้", 0, 0),
    Triple("สัปดาห์นี้", -6, 0),
    Triple("อาทิตย์นี้", -6, 0),
    Triple("สัปดาห์ที่แล้ว", -13, -7),
    Triple("อาทิตย์ที่แล้ว", -13, -7),
  )

  fun find(normalizedQuestion: String, nowEpochMs: Long, utcOffsetMinutes: Int): TimeRange? {
    val (word, first, last) = words.firstOrNull { it.first in normalizedQuestion } ?: return null
    val offset = utcOffsetMinutes * 60_000L
    val todayStart = (nowEpochMs + offset).floorDiv(DAY) * DAY - offset
    return TimeRange(word, todayStart + first * DAY, todayStart + (last + 1) * DAY)
  }

  /** Every UTC day (yyyy-MM-dd) whose file may hold records inside [range]. */
  fun utcDays(range: TimeRange): List<String> {
    val firstDay = range.fromEpochMs.floorDiv(DAY)
    val lastDay = (range.toEpochMs - 1).floorDiv(DAY)
    return (firstDay..lastDay).map { MemoryFormat.dayOf(it * DAY) }
  }

  val all: Set<String> get() = words.map { it.first }.toSet()
}
