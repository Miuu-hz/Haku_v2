package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.memory.MemoryFormat

/**
 * Facts the nightly job gathered. [lineCounts] = diary lines per candidate day after composing; [storedSummaryDays] =
 * days that already have a stored summary (EveningSummaryRepository.find != null).
 */
data class NightlyPlanInput(
  val onboarded: Boolean,
  val modelReady: Boolean,
  val nowUtcMs: Long,
  val tzOffsetMinutes: Int,
  val lineCounts: Map<String, Int>,
  val storedSummaryDays: Set<String>,
  /** Days that already have a Dream report (MemoryRepository.listDreamDays; the ledger day is never in it). */
  val dreamedDays: Set<String> = emptySet(),
)

/**
 * [summarizeDays] empty = do not load the model at all. [dreamDay] = the night Dream should run for (null = skip);
 * it is the local yesterday date used as-is as the Dream day (see [NightlyPlan.plan]).
 */
data class NightlyPlanOutput(val summarizeDays: List<String>, val dreamDay: String? = null)

/**
 * P2.7 nightly rules, pure. The job composes the diary for [candidateDays] only when onboarded ([shouldCompose]),
 * then asks [plan] which days deserve the model. A stored summary is final (EveningSummarizer stores once per day),
 * so today waits until [TODAY_FROM_HOUR]: summarizing at noon would freeze half a day.
 * ponytail: a day whose model text fails the check is retried on every run (only MODEL is stored); add an attempt
 * ledger if the Fold5 shows repeat loads while charging.
 */
object NightlyPlan {
  const val TODAY_FROM_HOUR = 21
  private const val MIN_MS = 60_000L
  private const val HOUR_MS = 3_600_000L
  private const val DAY_MS = 86_400_000L

  /** Yesterday and today, local days. */
  fun candidateDays(nowUtcMs: Long, tzOffsetMinutes: Int): List<String> {
    val local = nowUtcMs + tzOffsetMinutes * MIN_MS
    return listOf(MemoryFormat.dayOf(local - DAY_MS), MemoryFormat.dayOf(local))
  }

  fun shouldCompose(onboarded: Boolean): Boolean = onboarded

  /**
   * Dream (P1.8) needs no model (template wording), so it is planned independently of [NightlyPlanInput.modelReady].
   * D17: Dream's day is the user's local yesterday date taken as-is. Dream reads daily files (UTC days) up to and
   * including that name, which covers all of local yesterday east of UTC (Bangkok: local 00:00-07:00 sits in the
   * previous UTC file, 07:00-24:00 in the same one) and the report is named by the date the user lived. Once per
   * night: skipped when that day already has a report (DreamEngine is idempotent too; this avoids even building it).
   */
  fun plan(input: NightlyPlanInput): NightlyPlanOutput {
    if (!input.onboarded) return NightlyPlanOutput(emptyList())
    val (yesterday, today) = candidateDays(input.nowUtcMs, input.tzOffsetMinutes)
    val dreamDay = yesterday.takeIf { it !in input.dreamedDays }
    if (!input.modelReady) return NightlyPlanOutput(emptyList(), dreamDay)
    val localHour = ((input.nowUtcMs + input.tzOffsetMinutes * MIN_MS).mod(DAY_MS) / HOUR_MS).toInt()
    val days = listOfNotNull(yesterday, today.takeIf { localHour >= TODAY_FROM_HOUR })
      .filter { (input.lineCounts[it] ?: 0) > 0 && it !in input.storedSummaryDays }
    return NightlyPlanOutput(days, dreamDay)
  }
}
