package com.bbpillow.haku.surface.home

import com.bbpillow.haku.action.PendingOfferRepository
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureRecorder
import com.bbpillow.haku.capture.DwellRecord
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.capture.appointmentTitle
import com.bbpillow.haku.capture.appointmentsOfDay
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.capture.stats.StatsDataInput
import com.bbpillow.haku.capture.stats.StatsResult
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.surface.stats.StatsPresenter
import com.bbpillow.haku.task.TaskCounts
import com.bbpillow.haku.task.TaskRepository
import com.bbpillow.haku.task.TaskRules
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

/** One appointment row: local start `HH:MM`. [isPast] = already ended; [isNext] = the first one not ended. */
data class AppointmentRow(val title: String, val timeText: String, val isPast: Boolean, val isNext: Boolean)

/** "วันนี้มีนัด" card: at most [HomeBriefPresenter.APPOINTMENT_ROWS] rows; [moreCount] = today's appointments not shown. */
data class TodayAppointments(val rows: List<AppointmentRow>, val moreCount: Int)

/** A4 learning card. Nothing produces it yet (P5.x); the slot stays empty until then. */
data class LearningCard(val title: String, val summary: String, val topic: String, val source: String)

/**
 * One row of "รอคุณตัดสิน". [id] goes back to the confirm and dismiss calls.
 * [heading] "เจอนัดจาก LINE"; [detail] "ประชุมลูกค้า · พฤ 5 พ.ย. · 09:00–10:00"; [warning] a calendar conflict, or null.
 */
data class HomeDecisionRow(val id: String, val heading: String, val detail: String, val warning: String?)

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
  /** Null when today has no appointment; the card then says so. */
  val appointments: TodayAppointments? = null,
  /** Offers waiting for the user's tap, soonest first. The card is hidden when empty. */
  val decisions: List<HomeDecisionRow> = emptyList(),
  /** Open tasks per box, plus today's appointments still ahead under urgent. All zero still draws the card. */
  val taskCounts: TaskCounts = TaskCounts(),
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
  /** Null only in tests that predate it. */
  private val offers: PendingOfferRepository? = null,
  /** Null only in tests that predate it. */
  private val tasks: TaskRepository? = null,
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
    val known = places.list()
    val visits = visitsIn(storyStart, storyEnd, nowUtcMs, known)
    val stats = statsOf(visits, known)
    val stayedLine = if (lines.isEmpty()) stayedLine(visits, known, storyStart, storyEnd, storyDay == today) else null

    val todayEvents = events.appointmentsOfDay(dayStartUtc(today))
    val left = todayEvents.count { (it.endUtcMs ?: it.timeUtcMs) > nowUtcMs }
    val all = todayEvents.size

    offers?.deleteExpired(nowUtcMs)
    val decisions = offers?.active(nowUtcMs).orEmpty().map {
      val heading = if (it.appName != null) "เจอนัดจาก ${it.appName}" else "เจอนัดจากข้อความที่เลือก"
      HomeDecisionRow(it.id, heading, "${it.title} · ${it.detail}", it.warning)
    }
    // an appointment still ahead today is work due today: urgent by the task rule, and the user has not marked it important
    val taskCounts = TaskRules.counts(tasks?.all().orEmpty(), nowUtcMs).let { it.copy(urgent = it.urgent + left) }
    val isDayOne = lines.isEmpty() && stats == null && diary.readDay(today, tzOffsetMinutes).isEmpty() &&
      events.query(CaptureQueryInput(dayStartUtc(today) - DAY_MS, nowUtcMs)).isEmpty() &&
      decisions.isEmpty() && taskCounts.total == 0

    val headline = when {
      isDayOne -> "Haku กำลังเรียนรู้วันของคุณ"
      left == 0 && period == BriefPeriod.MORNING -> "เมื่อวานเป็นยังไงบ้าง"
      left == 0 && period == BriefPeriod.MIDDAY -> "บ่ายนี้มีอะไรบ้าง"
      left == 0 -> "วันนี้เป็นยังไงบ้าง"
      left == all -> "วันนี้มี $all นัด"
      else -> "วันนี้เหลืออีก $left นัด"
    }
    val name = style.addressName?.let { " $it" }.orEmpty()
    // by the clock alone: before dawn the brief still looks back at the day that ended, but the greeting is for a new morning
    val greeting = when {
      hour < MIDDAY_START_HOUR -> "สวัสดีตอนเช้า$name"
      hour < EVENING_START_HOUR -> "สวัสดีตอนกลางวัน$name"
      else -> "สวัสดีตอนเย็น$name"
    }
    val storyTitle = when {
      storyDay != today -> "เมื่อวานของคุณ"
      period == BriefPeriod.MIDDAY -> "วันนี้จนถึงตอนนี้"
      else -> "เรื่องของวันนี้"
    }
    return HomeBriefUiState(
      period = period,
      greeting = greeting,
      headline = headline,
      storyTitle = storyTitle,
      storyDay = storyDay,
      storyHeader = ThaiDateFormat.dayHeader(storyDay),
      storyLines = stayedLine?.let(::listOf) ?: lines.take(STORY_LINES).map { it.text },
      storyLineCount = lines.size,
      stats = stats,
      // a summary with no diary line left behind it (all deleted) is not shown
      storySummary = if (lines.isEmpty()) null else summaries.find(storyDay)?.text,
      weekly = if (isDayOne) null else weekly(nowUtcMs, style),
      isDayOne = isDayOne,
      appointments = appointments(todayEvents, nowUtcMs),
      decisions = decisions,
      taskCounts = taskCounts,
    )
  }

  /**
   * Calendar rows starting on the local [today] (deleted ones are gone from the store), in start order. When there are
   * more than [APPOINTMENT_ROWS], past ones drop first so the next appointment stays on screen.
   * ponytail: an appointment that started yesterday and runs into today is not listed; add an overlap query if users ask.
   */
  private fun appointments(all: List<CaptureEvent>, nowUtcMs: Long): TodayAppointments? {
    if (all.isEmpty()) return null
    val nextIdx = all.indexOfFirst { (it.endUtcMs ?: it.timeUtcMs) > nowUtcMs }.takeIf { it >= 0 } ?: all.size
    val from = minOf(nextIdx, all.size - APPOINTMENT_ROWS).coerceAtLeast(0)
    val rows = all.drop(from).take(APPOINTMENT_ROWS).mapIndexed { i, e ->
      AppointmentRow(e.appointmentTitle(), hhmm(e.timeUtcMs), isPast = from + i < nextIdx, isNext = from + i == nextIdx)
    }
    return TodayAppointments(rows, all.size - rows.size)
  }

  private fun hhmm(utcMs: Long) = MemoryFormat.formatUtc(utcMs + tzOffsetMinutes * MIN_MS).substring(11, 16)

  /**
   * Stays cut to [from, to). A stay still open now counts up to now, or a day spent in one place would have no numbers.
   * ponytail: an open stay whose end was never seen (capture turned off, phone dead) reads as ongoing for up to
   * [CaptureRecorder.LOOKBACK_MS], the same limit the recorder itself uses; ceiling accepted until capture gaps are marked.
   */
  private fun visitsIn(from: Long, to: Long, nowUtcMs: Long, known: List<PlaceRecord>): List<PlaceVisitRecord> {
    if (to <= from) return emptyList()
    val captured = events.query(CaptureQueryInput(from - CaptureRecorder.LOOKBACK_MS, nowUtcMs + 1))
    val open = PlaceRecognizer.openDwell(captured)?.let { start ->
      start.place?.let { DwellRecord(start.timeUtcMs, nowUtcMs, it.lat, it.lon) }
    }
    val recognized = PlaceRecognizer.recognize(
      PlaceRecognitionInput(PlaceRecognizer.pairDwells(captured) + listOfNotNull(open), known, tzOffsetMinutes),
    )
    return recognized.visits
      .map { it.copy(startUtcMs = maxOf(it.startUtcMs, from), endUtcMs = minOf(it.endUtcMs, to)) }
      .filter { it.endUtcMs > it.startUtcMs }
  }

  /** Stands in for the diary on a day with no line because nothing changed: one place from [from] to [to]. */
  private fun stayedLine(visits: List<PlaceVisitRecord>, known: List<PlaceRecord>, from: Long, to: Long, isToday: Boolean): String? {
    val placeId = visits.map { it.placeId }.distinct().singleOrNull() ?: return null
    if (visits.minOf { it.startUtcMs } != from || visits.maxOf { it.endUtcMs } != to) return null
    val where = known.firstOrNull { it.id == placeId && it.confirmed }?.label?.removePrefix("ที่") ?: "เดิม"
    return "อยู่ที่$where" + if (isToday) "มาตลอดวัน" else "ทั้งวัน"
  }

  /**
   * Number of places, and time away from the confirmed home within the span the stays cover. Null without a stay.
   * ponytail: time between the first and last stay counts as "out" unless at home, so a phone left off at home reads as out; ceiling accepted until P2 capture gaps are marked.
   */
  private fun statsOf(visits: List<PlaceVisitRecord>, known: List<PlaceRecord>): HomeStats? {
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
   * P2.8 stats screen (week/month/year), on the same stores; reached from the weekly card.
   * ponytail: the year tab re-reads ~2 years of raw events and re-recognizes stays on each open; cache visits if it gets slow.
   */
  val stats = StatsPresenter(weekStats, tzOffsetMinutes) { from, to ->
    val captured = events.query(CaptureQueryInput(from, to))
    val known = places.list()
    StatsDataInput(tzOffsetMinutes, known, PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(captured), known, tzOffsetMinutes)).visits, captured)
  }

  /** This week's lines, or null when the week has no recorded day (never a card of zeros). */
  private fun weekly(nowUtcMs: Long, style: DiaryStyle): WeeklyCard? {
    val summary = (stats.summarize(PeriodKind.WEEK, nowUtcMs, style) as? StatsResult.Ready)?.summary ?: return null
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
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
  }
}
