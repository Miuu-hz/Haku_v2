package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.PlaceRef

/** Subscription tier (doc 01 section 9.2, P9.4). Only FREE and PLUS matter to statistics; the free tier never loses data, only views. */
enum class Plan { FREE, PLUS }

/** What statistics can show. Which plan gets which is decided once, in [allows]. */
enum class StatsFeature { WEEK_SUMMARY, MONTH_SUMMARY, YEAR_SUMMARY, PERIOD_COMPARISON }

/** Supplied by the app (billing, P9.4). Offline-checkable by design; statistics only ask, never store. */
interface Entitlements {
  val plan: Plan
}

/** P2.8 gating. The plan does not say: free = this week's own numbers, no history and no comparison; PLUS = everything. */
fun Entitlements.allows(feature: StatsFeature): Boolean = when (feature) {
  StatsFeature.WEEK_SUMMARY -> true
  StatsFeature.MONTH_SUMMARY, StatsFeature.YEAR_SUMMARY, StatsFeature.PERIOD_COMPARISON -> plan == Plan.PLUS
}

enum class PeriodKind(val feature: StatsFeature) {
  WEEK(StatsFeature.WEEK_SUMMARY),
  MONTH(StatsFeature.MONTH_SUMMARY),
  YEAR(StatsFeature.YEAR_SUMMARY),
}

/** A local calendar span. [startDay] inclusive, [endDay] exclusive (`yyyy-MM-dd`); UTC bounds already shifted by the timezone (D17). Weeks run Monday to Sunday. */
data class StatsPeriod(
  val kind: PeriodKind,
  val startDay: String,
  val endDay: String,
  val fromUtcMs: Long,
  val toUtcMs: Long,
  val dayCount: Int,
)

/** One place's use in a period. [ref] follows the diary rule: only confirmed places carry a name (D14). */
data class PlaceUse(val placeId: String, val ref: PlaceRef, val category: PlaceCategory, val visits: Int, val totalMs: Long)

/** [coveredDays] = days of this weekday that have any recorded stay; [otherMs] = time there away from both home and work. */
data class WeekdayUse(val coveredDays: Int, val otherMs: Long)

/**
 * Measured numbers for one period. Null means "cannot be known" and is never rendered: [homeMs] without a confirmed
 * home, [workMs] without a confirmed work, [otherMs] and the weekday split without both, [newPlaces] without any
 * history before the period (else every place would look new).
 */
data class PeriodStats(
  val period: StatsPeriod,
  val coveredDays: Int,
  val recordedMs: Long,
  val homeMs: Long?,
  val workMs: Long?,
  val otherMs: Long?,
  val distinctPlaces: Int,
  val newPlaces: Int?,
  /** Non-home places, most visits first (ties: more time, then id). */
  val placeUses: List<PlaceUse>,
  val travelMs: Long,
  val appointments: Int,
  /** Monday = index 0. */
  val weekdays: List<WeekdayUse>,
) {
  val awayFromHomeMs: Long? get() = homeMs?.let { recordedMs - it }

  /** At least half the period's days have data; below that no comparison is made (both sides must pass). */
  val isReliable: Boolean get() = coveredDays * 2 >= period.dayCount
}

data class PeriodStatsInput(
  val period: StatsPeriod,
  val tzOffsetMinutes: Int,
  val places: List<PlaceRecord>,
  /** Recognized stays, including history before the period (needed to tell a new place from a first-ever one). */
  val visits: List<PlaceVisitRecord>,
  val events: List<CaptureEvent>,
)

/** A claim about the numbers. Each carries the numbers it stands on, so the UI can show its evidence. */
sealed interface StatsInsight {
  /** [weekday] (Monday = 0) has the highest average time away from home and work, [avgOtherMs] over [occurrences] days; the runner-up averages [runnerUpAvgMs]. */
  data class BusiestWeekday(val weekday: Int, val avgOtherMs: Long, val occurrences: Int, val runnerUpAvgMs: Long) : StatsInsight

  /** Average time away from home per recorded day, both periods measured. */
  data class AwayFromHomeChanged(val currentAvgMs: Long, val previousAvgMs: Long, val currentDays: Int, val previousDays: Int) : StatsInsight

  /** Visits to one named place, both periods measured over their recorded days. */
  data class PlaceVisitsChanged(
    val place: PlaceUse,
    val current: Int,
    val previous: Int,
    val currentDays: Int,
    val previousDays: Int,
  ) : StatsInsight
}

/** How a summary calls its periods: "เดือนนี้"/"เดือนก่อน" when [PeriodStats.period] holds today, dates otherwise. */
data class StatsSummaryInput(val kind: PeriodKind, val day: String, val nowUtcMs: Long, val style: DiaryStyle = DiaryStyle())

/** [previous] and comparison [insights] exist only when the plan allows and both periods have enough data. */
data class StatsSummaryOutput(
  val current: PeriodStats,
  val previous: PeriodStats?,
  val insights: List<StatsInsight>,
  val lines: List<String>,
  /** The part of [lines] that compares with [previous] (empty without a comparison); the stats screen shows it apart. */
  val comparisonLines: List<String> = emptyList(),
)

/** Either the summary, or which feature the plan lacks (the screen shows an upsell; nothing was deleted). */
sealed interface StatsResult {
  data class Ready(val summary: StatsSummaryOutput) : StatsResult
  data class Locked(val feature: StatsFeature) : StatsResult
}
