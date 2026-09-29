package com.bbpillow.haku.language

import com.bbpillow.haku.memory.MemoryFormat

/** Display-only Thai dates (D17): the system stores CE + UTC; Buddhist Era exists only in what the user reads. */
object ThaiDateFormat {
  private const val BE_OFFSET = 543
  private const val DAY_MS = 86_400_000L
  private val weekdays = listOf("อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์")
  private val months = listOf(
    "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
    "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
  )

  /** `2026-09-07` (local calendar date) -> `วันจันทร์ที่ 7 กันยายน พ.ศ. 2569`. */
  fun dayHeader(day: String): String {
    val ms = requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" }
    val (y, m, d) = day.split('-').map { it.toInt() }
    val weekday = weekdays[(ms.floorDiv(DAY_MS) + 4).mod(7L).toInt()] // 1970-01-01 was a Thursday
    return "วัน${weekday}ที่ $d ${months[m - 1]} พ.ศ. ${y + BE_OFFSET}"
  }
}
