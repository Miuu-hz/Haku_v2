package com.bbpillow.haku.surface.stats

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.capture.stats.StatsDataInput
import com.bbpillow.haku.capture.stats.StatsFeature
import com.bbpillow.haku.capture.stats.StatsPeriods
import com.bbpillow.haku.capture.stats.StatsResult
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.capture.stats.StatsSummaryInput
import com.bbpillow.haku.capture.stats.StatsTemplates
import com.bbpillow.haku.capture.stats.allows
import com.bbpillow.haku.memory.MemoryFormat

/** One period tab. [locked] = the plan does not include it (FREE: month and year); tapping it shows the Plus upsell. */
data class StatsTab(val kind: PeriodKind, val label: String, val locked: Boolean)

/**
 * The stats screen (P2.8). [lines] = the selected period's measured lines, null when the tab is locked (nothing is
 * computed, nothing deleted). [comparison] = lines against the previous period of the same kind, null when not shown
 * (locked, or no data this period). [comparisonLocked] = the plan has no comparison (FREE).
 */
data class StatsUiState(
  val tabs: List<StatsTab>,
  val selected: PeriodKind,
  val lines: List<String>?,
  val comparison: List<String>? = null,
  val comparisonLocked: Boolean = false,
) {
  val previousLabel: String get() = StatsTemplates.lastPeriod(selected)
}

/**
 * Week/month/year statistics up to now, always the period that holds today. [dataBetween] loads places, recognized
 * stays and raw events recorded in [from, to) (UTC ms); the history before the period lets "new place" mean something.
 */
class StatsPresenter(
  private val summarizer: StatsSummarizer,
  private val tzOffsetMinutes: Int,
  private val dataBetween: (fromUtcMs: Long, toUtcMs: Long) -> StatsDataInput,
) {
  fun load(kind: PeriodKind, nowUtcMs: Long, style: DiaryStyle = DiaryStyle()): StatsUiState {
    val tabs = PeriodKind.entries.map { StatsTab(it, StatsTemplates.thisPeriod(it), !summarizer.entitlements.allows(it.feature)) }
    if (tabs.first { it.kind == kind }.locked) return StatsUiState(tabs, kind, lines = null) // no data read for a locked view
    val summary = (summarize(kind, nowUtcMs, style) as? StatsResult.Ready)?.summary ?: return StatsUiState(tabs, kind, lines = null)
    val comparisonLocked = !summarizer.entitlements.allows(StatsFeature.PERIOD_COMPARISON)
    val previous = summary.previous
    val comparison = if (previous == null || summary.current.coveredDays == 0) null else {
      val prevCoverage = StatsTemplates.coverage(StatsTemplates.lastPeriod(kind), previous).takeIf { previous.coveredDays > 0 }
      val claims = summary.comparisonLines.ifEmpty { listOf(style.particle.appendTo(StatsTemplates.noComparison(kind, summary.current, previous))) }
      listOfNotNull(prevCoverage?.let(style.particle::appendTo)) + claims
    }
    return StatsUiState(tabs, kind, summary.lines - summary.comparisonLines.toSet(), comparison, comparisonLocked)
  }

  /** The summarizer's result for the period of [kind] holding now; history reaches back before the previous period. */
  fun summarize(kind: PeriodKind, nowUtcMs: Long, style: DiaryStyle = DiaryStyle()): StatsResult {
    val today = MemoryFormat.dayOf(nowUtcMs + tzOffsetMinutes * MIN_MS)
    val previous = StatsPeriods.previous(StatsPeriods.of(kind, today, tzOffsetMinutes), tzOffsetMinutes)
    val data = dataBetween(previous.fromUtcMs - HISTORY_DAYS * StatsPeriods.DAY_MS, nowUtcMs)
    return summarizer.summarize(StatsSummaryInput(kind, today, nowUtcMs, style), data)
  }

  companion object {
    /** History before the earliest period read, so a place seen last month is not "new". */
    const val HISTORY_DAYS = 28
    private const val MIN_MS = 60_000L
  }
}
