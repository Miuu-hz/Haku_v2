package com.bbpillow.haku.surface.home

import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.CaptureTypes
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.capture.stats.StatsDataInput
import com.bbpillow.haku.capture.stats.StatsPeriods
import com.bbpillow.haku.capture.stats.StatsResult
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.capture.stats.StatsSummaryInput
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.thinking.summary.EveningSummaryRepository

/** Part of the day the brief is shaped for (P3.1): morning looks back at yesterday, midday and evening at today. */
enum class BriefPeriod { MORNING, MIDDAY, EVENING }

/**
 * Numbers for the story day, all measured. [outOfHomeFraction]/[outOfHomeMinutes] are null without a confirmed home
 * or without any recorded stay; [steps] is null until a step source exists. Never filled with estimates.
 */
data class HomeStats(
  val placeCount: Int,
  val outOfHomeFraction: Float? = null,
  val outOfHomeMinutes: Int? = null,
  val steps: Int? = null,
)

/**
 * A5 weekly card: this week's measured lines from StatsSummarizer (P2.8). [comparisonLocked] = the plan has no
 * comparison with last week (FREE); the lines then never compare.
 */
data class WeeklyCard(val lines: List<String>, val comparisonLocked: Boolean)

/** One appointment row: local `HH:MM` or `HH:MM–HH:MM`. [isPast] = already ended; [isNext] = the first one not ended. */
data class AppointmentRow(val title: String, val timeText: String, val isPast: Boolean, val isNext: Boolean)

/** "วันนี้มีนัด" card: at most [HomeBriefPresenter.APPOINTMENT_ROWS] rows; [moreCount] = today's appointments not shown. */
data class TodayAppointments(val rows: List<AppointmentRow>, val moreCount: Int)

/** A4 learning card. Nothing produces it yet (P5.x); the slot stays empty until then. */
data class LearningCard(val title: String, val summary: String, val topic: String, val source: String)

/**
 * Everything the Home Brief draws. [storySummary] = the stored evening summary of [storyDay] (P2.7, model or template),
 * shown instead of [storyLines] (the first rule-based diary lines, P2.6) when present; both empty = "no summary yet".
 * [weekly] is null until this week has a recorded day. [isDayOne] = nothing recorded yet at all (A6).
 */
data class HomeBriefUiState(
  val period: BriefPeriod,
  val greeting: String,
  val headline: String,
  val storyTitle: String,
  val storyDay: String,
  val storyHeader: String,
  val storyLines: List<String>,
  val storyLineCount: Int,
  val stats: HomeStats?,
  val storySummary: String? = null,
  val weekly: WeeklyCard? = null,
  val learning: LearningCard? = null,
  val isDayOne: Boolean = false,
  /** Null when today has no appointment (the card is hidden, never a zero). */
  val appointments: TodayAppointments? = null,
)

/**
 * Builds the Home Brief from the user's own diary and stays (P3.1). No model call: three parts of the day give three
 * different briefs from the same data. Stateless; the screen only holds the returned state.
 */
class HomeBriefPresenter(
  private val diary: DiaryComposer,
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val tzOffsetMinutes: Int,
  private val summaries: EveningSummaryRepository,
  private val weekStats: StatsSummarizer,
) {
  fun load(nowUtcMs: Long, style: DiaryStyle = DiaryStyle()): HomeBriefUiState {
    val localMs = nowUtcMs + tzOffsetMinutes * MIN_MS
    val today = MemoryFormat.dayOf(localMs)
    val hour = ((localMs % DAY_MS) / HOUR_MS).toInt()
    val period = periodOf(hour)
    // before dawn the evening brief still talks about the day that just ended
    val storyDay = if (period == BriefPeriod.MORNING || hour < MORNING_START_HOUR) plusDays(today, -1) else today
    val lines = diary.readDay(storyDay, tzOffsetMinutes)

    val storyStart = dayStartUtc(storyDay)
    val storyEnd = minOf(storyStart + DAY_MS, nowUtcMs)
    val stats = statsFor(storyStart, storyEnd)
    val isDayOne = lines.isEmpty() && stats == null && diary.readDay(today, tzOffsetMinutes).isEmpty() &&
      events.query(CaptureQueryInput(dayStartUtc(today) - DAY_MS, nowUtcMs)).isEmpty()

    val name = style.addressName?.let { " $it" }.orEmpty()
    return HomeBriefUiState(
      period = period,
      greeting = when (period) {
        BriefPeriod.MORNING -> "อรุณสวัสดิ์$name"
        BriefPeriod.MIDDAY -> "สวัสดีตอนบ่าย$name"
        BriefPeriod.EVENING -> "สวัสดีตอนเย็น$name"
      },
      headline = when {
        isDayOne -> "Haku กำลังเรียนรู้วันของคุณ"
        period == BriefPeriod.MORNING -> "เมื่อวานเป็นยังไงบ้าง"
        period == BriefPeriod.MIDDAY -> "บ่ายนี้มีอะไรบ้าง"
        else -> "วันนี้เป็นยังไงบ้าง"
      },
      storyTitle = when {
        storyDay != today -> "เมื่อวาน"
        period == BriefPeriod.MIDDAY -> "วันนี้จนถึงตอนนี้"
        else -> "เรื่องของวันนี้"
      },
      storyDay = storyDay,
      storyHeader = ThaiDateFormat.dayHeader(storyDay),
      storyLines = lines.take(STORY_LINES).map { it.text },
      storyLineCount = lines.size,
      stats = stats,
      // a summary with no diary line left behind it (all deleted) is not shown
      storySummary = if (lines.isEmpty()) null else summaries.find(storyDay)?.text,
      weekly = if (isDayOne) null else weekly(today, nowUtcMs, style),
      isDayOne = isDayOne,
      appointments = appointments(today, nowUtcMs),
    )
  }

  /**
   * Calendar rows starting on the local [today] (deleted ones are gone from the store), in start order. When there are
   * more than [APPOINTMENT_ROWS], past ones drop first so the next appointment stays on screen.
   * ponytail: an appointment that started yesterday and runs into today is not listed; add an overlap query if users ask.
   */
  private fun appointments(today: String, nowUtcMs: Long): TodayAppointments? {
    val start = dayStartUtc(today)
    val all = events.query(CaptureQueryInput(start, start + DAY_MS, setOf(CaptureTypes.CALENDAR)))
      .filter { it.source is CaptureSource.CalendarEntry }
    if (all.isEmpty()) return null
    val nextIdx = all.indexOfFirst { (it.endUtcMs ?: it.timeUtcMs) > nowUtcMs }.takeIf { it >= 0 } ?: all.size
    val from = minOf(nextIdx, all.size - APPOINTMENT_ROWS).coerceAtLeast(0)
    val rows = all.drop(from).take(APPOINTMENT_ROWS).mapIndexed { i, e ->
      val title = (e.source as CaptureSource.CalendarEntry).title.trim().ifEmpty { UNTITLED_APPOINTMENT }
      val time = hhmm(e.timeUtcMs) + (e.endUtcMs?.let { "–" + hhmm(it) } ?: "")
      AppointmentRow(title, time, isPast = from + i < nextIdx, isNext = from + i == nextIdx)
    }
    return TodayAppointments(rows, all.size - rows.size)
  }

  private fun hhmm(utcMs: Long) = MemoryFormat.formatUtc(utcMs + tzOffsetMinutes * MIN_MS).substring(11, 16)

  /**
   * Stays in [from, to): number of places, and time away from the confirmed home within the span the stays cover.
   * Null when no stay touched the window.
   * ponytail: time between the first and last stay counts as "out" unless at home, so a phone left off at home reads as out; ceiling accepted until P2 capture gaps are marked.
   */
  private fun statsFor(from: Long, to: Long): HomeStats? {
    if (to <= from) return null
    val known = places.list()
    val recognized = PlaceRecognizer.recognize(
      PlaceRecognitionInput(PlaceRecognizer.pairDwells(events.query(CaptureQueryInput(from - DAY_MS, to))), known, tzOffsetMinutes),
    )
    val visits = recognized.visits
      .map { it.copy(startUtcMs = maxOf(it.startUtcMs, from), endUtcMs = minOf(it.endUtcMs, to)) }
      .filter { it.endUtcMs > it.startUtcMs }
    if (visits.isEmpty()) return null

    val homeIds = known.filter { it.confirmed && it.category == PlaceCategory.HOME }.map { it.id }.toSet()
    val placeCount = visits.map { it.placeId }.distinct().size
    if (homeIds.isEmpty()) return HomeStats(placeCount)
    val covered = visits.maxOf { it.endUtcMs } - visits.minOf { it.startUtcMs }
    val home = visits.filter { it.placeId in homeIds }.sumOf { it.endUtcMs - it.startUtcMs }
    val out = (covered - home).coerceAtLeast(0)
    return HomeStats(placeCount, out.toFloat() / covered, (out / MIN_MS).toInt())
  }

  /**
   * This week's lines, or null when the week has no recorded day (never a card of zeros).
   * ponytail: four weeks of history before the week so "new place" means something; widen when month views land on Home.
   */
  private fun weekly(today: String, nowUtcMs: Long, style: DiaryStyle): WeeklyCard? {
    val week = StatsPeriods.of(PeriodKind.WEEK, today, tzOffsetMinutes)
    val captured = events.query(CaptureQueryInput(week.fromUtcMs - WEEKLY_HISTORY_DAYS * DAY_MS, nowUtcMs))
    val known = places.list()
    val visits = PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(captured), known, tzOffsetMinutes)).visits
    val result = weekStats.summarize(StatsSummaryInput(PeriodKind.WEEK, today, nowUtcMs, style), StatsDataInput(tzOffsetMinutes, known, visits, captured))
    val summary = (result as? StatsResult.Ready)?.summary ?: return null
    if (summary.current.coveredDays == 0) return null
    return WeeklyCard(summary.lines, comparisonLocked = summary.previous == null)
  }

  private fun periodOf(hour: Int) = when (hour) {
    in MORNING_START_HOUR until MIDDAY_START_HOUR -> BriefPeriod.MORNING
    in MIDDAY_START_HOUR until EVENING_START_HOUR -> BriefPeriod.MIDDAY
    else -> BriefPeriod.EVENING
  }

  private fun dayStartUtc(day: String): Long =
    requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } - tzOffsetMinutes * MIN_MS

  private fun plusDays(day: String, n: Int): String =
    MemoryFormat.dayOf(requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } + n * DAY_MS)

  companion object {
    const val MORNING_START_HOUR = 4
    const val MIDDAY_START_HOUR = 11
    const val EVENING_START_HOUR = 17
    const val STORY_LINES = 3
    const val APPOINTMENT_ROWS = 4
    private const val UNTITLED_APPOINTMENT = "นัดไม่มีชื่อ"
    private const val WEEKLY_HISTORY_DAYS = 28
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
  }
}
