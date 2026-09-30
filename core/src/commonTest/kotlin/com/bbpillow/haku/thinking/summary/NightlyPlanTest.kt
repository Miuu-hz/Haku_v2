package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightlyPlanTest {
  private val tz = 420 // Bangkok
  private fun localUtc(local: String) = MemoryFormat.parseUtc("${local}:00.000Z")!! - tz * 60_000L
  private val night = localUtc("2026-10-01T22:00") // after TODAY_FROM_HOUR
  private val afternoon = localUtc("2026-10-01T14:00")
  private val both = mapOf("2026-09-30" to 5, "2026-10-01" to 3)

  private fun plan(
    now: Long = night,
    onboarded: Boolean = true,
    modelReady: Boolean = true,
    lines: Map<String, Int> = both,
    stored: Set<String> = emptySet(),
  ) = NightlyPlan.plan(NightlyPlanInput(onboarded, modelReady, now, tz, lines, stored)).summarizeDays

  @Test fun candidatesAreLocalYesterdayAndToday() {
    assertEquals(listOf("2026-09-30", "2026-10-01"), NightlyPlan.candidateDays(night, tz))
    // 00:30 local is still the UTC day before: the local day wins
    assertEquals(listOf("2026-09-30", "2026-10-01"), NightlyPlan.candidateDays(localUtc("2026-10-01T00:30"), tz))
  }

  @Test fun nothingBeforeOnboarding() {
    assertFalse(NightlyPlan.shouldCompose(false))
    assertTrue(NightlyPlan.shouldCompose(true))
    assertEquals(emptyList(), plan(onboarded = false))
  }

  @Test fun noModelFileMeansDiaryOnly() = assertEquals(emptyList(), plan(modelReady = false))

  @Test fun atNightBothDaysAreSummarized() = assertEquals(listOf("2026-09-30", "2026-10-01"), plan())

  @Test fun todayWaitsForTheEvening() = assertEquals(listOf("2026-09-30"), plan(afternoon))

  @Test fun storedAndEmptyDaysAreSkipped() {
    assertEquals(listOf("2026-10-01"), plan(stored = setOf("2026-09-30")))
    assertEquals(listOf("2026-10-01"), plan(lines = mapOf("2026-09-30" to 0, "2026-10-01" to 2)))
    assertEquals(emptyList(), plan(stored = both.keys)) // second run the same night: no model load
  }

  private fun dreamDay(onboarded: Boolean = true, modelReady: Boolean = true, dreamed: Set<String> = emptySet(), now: Long = night) =
    NightlyPlan.plan(NightlyPlanInput(onboarded, modelReady, now, tz, both, emptySet(), dreamed)).dreamDay

  @Test fun dreamRunsForLocalYesterdayEvenWithoutAModel() {
    assertEquals("2026-09-30", dreamDay(modelReady = false))
    assertEquals("2026-09-30", dreamDay(now = afternoon))
  }

  @Test fun dreamWaitsForOnboarding() = assertEquals(null, dreamDay(onboarded = false))

  @Test fun dreamRunsOncePerNight() {
    assertEquals(null, dreamDay(dreamed = setOf("2026-09-30")))
    assertEquals("2026-09-30", dreamDay(dreamed = setOf("2026-09-29")))
  }
}
