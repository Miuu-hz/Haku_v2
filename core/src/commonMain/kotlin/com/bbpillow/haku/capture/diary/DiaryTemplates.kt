package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.PlaceCategory
import kotlin.math.round

/**
 * DEVELOPER REVIEW: every Thai sentence the rule-based diary can say lives in this file and nowhere else (D14).
 * Have a Thai speaker read each function's output before release. Wording changes here never change line ids
 * (ids come from source events), so lines already written keep their old wording.
 *
 * Shapes (time is always first, local 24h HH:MM):
 *   08:15 ออกจากบ้าน
 *   09:00 ถึงที่ทำงาน ใช้เวลาเดินทางราว 45 นาที อยู่ราว 3 ชั่วโมงครึ่ง
 *   15:00 มีนัด “หมอฟัน” ถึง 16:00
 */
object DiaryTemplates {
  const val UNKNOWN_PLACE = "สถานที่หนึ่ง" // a place Haku has not named yet (PlaceNames runs nightly)
  const val HOME = "บ้าน"
  const val WORK = "ที่ทำงาน"
  private const val UNTITLED_APPOINTMENT = "มีนัดหนึ่งรายการ"
  private const val ROUND_MIN = 5L
  private const val HOUR_MIN = 60L
  private const val HALF_HOUR_MIN = 30L

  fun place(p: PlaceRef): String = when {
    !p.label.isNullOrBlank() -> p.label
    p.category == PlaceCategory.HOME -> HOME
    p.category == PlaceCategory.WORK -> WORK
    else -> UNKNOWN_PLACE
  }

  fun departure(from: PlaceRef) = "ออกจาก${place(from)}"

  fun arrival(at: PlaceRef, travelMs: Long?, stayMs: Long?): String = buildString {
    append("ถึง").append(place(at))
    if (travelMs != null) append(" ใช้เวลาเดินทางราว ").append(duration(travelMs))
    if (stayMs != null) append(" อยู่ราว ").append(duration(stayMs))
  }

  fun calendar(title: String, endHhmm: String?): String {
    val head = if (title.isBlank()) UNTITLED_APPOINTMENT else "มีนัด “${title.trim()}”"
    return head + (endHhmm?.let { " ถึง $it" } ?: "")
  }

  /** Spoken-style duration rounded to 5 min: 45 นาที, 1 ชั่วโมงครึ่ง, 2 ชั่วโมง 15 นาที. */
  fun duration(ms: Long): String {
    val total = (round(ms / 60_000.0 / ROUND_MIN).toLong() * ROUND_MIN).coerceAtLeast(ROUND_MIN)
    val h = total / HOUR_MIN
    val m = total % HOUR_MIN
    return when {
      h == 0L -> "$m นาที"
      m == 0L -> "$h ชั่วโมง"
      m == HALF_HOUR_MIN -> "$h ชั่วโมงครึ่ง"
      else -> "$h ชั่วโมง $m นาที"
    }
  }
}
