package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord

/** Raw material for any period: the caller loads places, all visits (history included) and events once. */
data class StatsDataInput(
  val tzOffsetMinutes: Int,
  val places: List<PlaceRecord>,
  val visits: List<PlaceVisitRecord>,
  val events: List<CaptureEvent>,
)

/** P2.8 entry point. Asks [Entitlements] once per feature; a locked view returns [StatsResult.Locked], never partial data. */
class StatsSummarizer(val entitlements: Entitlements) {
  fun summarize(input: StatsSummaryInput, data: StatsDataInput): StatsResult {
    if (!entitlements.allows(input.kind.feature)) return StatsResult.Locked(input.kind.feature)
    val period = StatsPeriods.of(input.kind, input.day, data.tzOffsetMinutes)
    val current = statsOf(period, data)
    val canCompare = entitlements.allows(StatsFeature.PERIOD_COMPARISON)
    val previous = if (canCompare) statsOf(StatsPeriods.previous(period, data.tzOffsetMinutes), data) else null
    val insights = StatsCalculator.insights(current) + previous?.let { StatsCalculator.compare(current, it) }.orEmpty()
    val holdsNow = input.nowUtcMs in period.fromUtcMs until period.toUtcMs
    val lines = lines(input, current, insights, holdsNow)
    val comparing = if (!holdsNow) emptyList() else insights.filter { it !is StatsInsight.BusiestWeekday }
      .map { input.style.particle.appendTo(StatsTemplates.insight(it, input.kind)) }.filter { it in lines }
    return StatsResult.Ready(StatsSummaryOutput(current, previous, insights, lines, comparing))
  }

  private fun statsOf(period: StatsPeriod, data: StatsDataInput) =
    StatsCalculator.compute(PeriodStatsInput(period, data.tzOffsetMinutes, data.places, data.visits, data.events))

  private fun lines(input: StatsSummaryInput, s: PeriodStats, insights: List<StatsInsight>, holdsNow: Boolean): List<String> {
    val who = if (holdsNow) StatsTemplates.thisPeriod(input.kind) else "${s.period.startDay} ถึง ${StatsPeriods.dayOf(StatsPeriods.epochDay(s.period.endDay) - 1)}"
    if (s.coveredDays == 0) return listOf(StatsTemplates.NO_DATA + input.style.particle.text)
    val body = listOfNotNull(StatsTemplates.coverage(who, s), StatsTemplates.timeSplit(s), StatsTemplates.places(s), StatsTemplates.travelAndAppointments(s))
    // Comparison wording says "this/last period"; only valid when the period holds now. Past periods keep evidence-free-of-naming claims out.
    val claims = insights.filter { holdsNow || it is StatsInsight.BusiestWeekday }.map { StatsTemplates.insight(it, input.kind) }
    return (body + claims).map { input.style.particle.appendTo(it) }
  }
}
