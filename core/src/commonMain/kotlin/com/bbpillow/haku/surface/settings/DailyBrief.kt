package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.thinking.summary.SummaryTemplate

/** P5.2 the two opt-in daily notifications. */
enum class BriefKind { MORNING, EVENING }

/** What one notification says. Only built when there is something real to say (see [DailyBrief.compose]). */
data class BriefNoticeOutput(val title: String, val text: String)

/**
 * P5.1/P5.2 pure parts of the daily brief: when it fires and what it says. No model call: the text is the stored
 * evening summary or the template over the day's diary lines, plus today's appointments in the morning.
 */
object DailyBrief {
  const val MORNING_DEFAULT_MINUTE = 7 * 60 + 30
  const val EVENING_DEFAULT_MINUTE = 21 * 60

  /** A run later than this after its time is dropped: a morning brief at 13:00 (phone was off) is noise, not help. */
  const val LATE_LIMIT_MS = 2 * 3_600_000L

  /** Lock-screen text (VISIBILITY_PRIVATE public version): never any content. */
  const val PUBLIC_TEXT = "Haku มีสรุปให้คุณ"
  const val EVENING_INVITE = "อยากเล่าอะไรเพิ่มไหม แตะเพื่อเปิด Haku"

  private const val MIN_MS = 60_000L
  private const val DAY_MS = 86_400_000L
  private const val DAY_MINUTES = 24 * 60

  fun isOn(profile: ProfileRecord, kind: BriefKind) = when (kind) {
    BriefKind.MORNING -> profile.morningBriefOn
    BriefKind.EVENING -> profile.eveningBriefOn
  }

  fun minuteOf(profile: ProfileRecord, kind: BriefKind) = when (kind) {
    BriefKind.MORNING -> profile.morningBriefMinute
    BriefKind.EVENING -> profile.eveningBriefMinute
  }

  /**
   * The next UTC instant strictly after [nowUtcMs] at local [minuteOfDay] (a time already passed today, or exactly now,
   * goes to tomorrow). Fixed offset: Asia/Bangkok has no DST.
   */
  fun nextFireUtc(nowUtcMs: Long, tzOffsetMinutes: Int, minuteOfDay: Int): Long {
    val local = nowUtcMs + tzOffsetMinutes * MIN_MS
    val todayFire = local.floorDiv(DAY_MS) * DAY_MS + minuteOfDay.coerceIn(0, DAY_MINUTES - 1) * MIN_MS
    val fire = if (todayFire > local) todayFire else todayFire + DAY_MS
    return fire - tzOffsetMinutes * MIN_MS
  }

  /** True when [nowUtcMs] is within [LATE_LIMIT_MS] after the most recent local [minuteOfDay]. */
  fun isOnTime(nowUtcMs: Long, tzOffsetMinutes: Int, minuteOfDay: Int): Boolean {
    val last = nextFireUtc(nowUtcMs, tzOffsetMinutes, minuteOfDay) - DAY_MS
    return nowUtcMs - last in 0..LATE_LIMIT_MS
  }

  /**
   * Text from the Home Brief state of the fire time (morning = yesterday's story, evening = today's). Null when there is
   * nothing to say: no diary line for the story day and (morning) no appointment today. Never a zero or a made-up line.
   */
  fun compose(kind: BriefKind, home: HomeBriefUiState, style: DiaryStyle): BriefNoticeOutput? {
    val story = when {
      home.storyLines.isEmpty() -> null
      else -> home.storySummary ?: SummaryTemplate.paragraph(home.storyDay, home.storyLines, style)
    }
    val appointments = home.appointments?.takeIf { kind == BriefKind.MORNING }?.let { a ->
      val next = a.rows.firstOrNull { it.isNext }
      val total = a.rows.size + a.moreCount
      "วันนี้มีนัด $total นัด" + (next?.let { " ถัดไป ${it.timeText.substringBefore('–')} ${it.title}" } ?: "")
    }
    if (story == null && appointments == null) return null
    val text = when (kind) {
      BriefKind.MORNING -> listOfNotNull(appointments, story?.let { "${home.storyTitle}: $it" })
      BriefKind.EVENING -> listOfNotNull(story, EVENING_INVITE)
    }.joinToString("\n")
    return BriefNoticeOutput(home.greeting, text)
  }

  fun formatMinute(minuteOfDay: Int): String {
    val m = minuteOfDay.coerceIn(0, DAY_MINUTES - 1)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
  }

  /** "HH:MM" → minute of day; anything else → null. */
  fun parseMinute(raw: String?): Int? {
    val parts = raw?.split(':')?.takeIf { it.size == 2 && it.all { p -> p.length == 2 } } ?: return null
    val h = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val m = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return h * 60 + m
  }
}
