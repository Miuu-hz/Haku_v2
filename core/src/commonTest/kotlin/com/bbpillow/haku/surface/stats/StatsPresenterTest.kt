package com.bbpillow.haku.surface.stats

import com.bbpillow.haku.capture.SyntheticMonth
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.capture.stats.StatsDataInput
import com.bbpillow.haku.capture.stats.StatsPeriods
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.capture.stats.StatsTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsPresenterTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val now = SyntheticWeek.at(23, 20) // Wed 2026-09-30 20:00, end of the synthetic month
  private val reads = mutableListOf<Long>()

  private fun presenter(plan: Plan, data: StatsDataInput = StatsDataInput(tz, SyntheticMonth.places, SyntheticMonth.visits(), emptyList())) =
    StatsPresenter(StatsSummarizer(object : Entitlements { override val plan = plan }), tz) { from, _ -> reads += from; data }

  @Test fun freeSeesThisWeekWithMonthYearAndComparisonLocked() {
    val s = presenter(Plan.FREE).load(PeriodKind.WEEK, now)
    assertEquals(listOf(false, true, true), s.tabs.map { it.locked })
    assertTrue(assertNotNull(s.lines).first().startsWith("สัปดาห์นี้ มีข้อมูล "))
    assertNull(s.comparison)
    assertTrue(s.comparisonLocked)
  }

  @Test fun freeMonthIsLockedAndReadsNothing() {
    val s = presenter(Plan.FREE).load(PeriodKind.MONTH, now)
    assertNull(s.lines)
    assertNull(s.comparison)
    assertTrue(reads.isEmpty())
  }

  @Test fun plusMonthSplitsOwnLinesFromTheComparison() {
    val s = presenter(Plan.PLUS).load(PeriodKind.MONTH, now)
    assertTrue(s.tabs.none { it.locked })
    assertFalse(s.comparisonLocked)
    val lines = assertNotNull(s.lines)
    val comparison = assertNotNull(s.comparison)
    assertEquals("เดือนนี้ มีข้อมูล 30 จาก 30 วัน", lines.first())
    assertTrue(lines.any { it.startsWith("วันเสาร์ออกไปข้างนอกบ่อยที่สุด") }) // a claim from this month alone stays above
    assertEquals("เดือนก่อน มีข้อมูล 31 จาก 31 วัน", comparison.first())
    assertTrue("เดือนนี้ไป “ร้านข้าว” 22 ครั้ง เดือนก่อน 0 ครั้ง" in comparison)
    assertTrue(lines.none { "เดือนก่อน" in it })
    assertEquals("เดือนก่อน", s.previousLabel)
    // history reaches back before the previous month
    assertEquals(StatsPeriods.of(PeriodKind.MONTH, "2026-08-01", tz).fromUtcMs - StatsPresenter.HISTORY_DAYS * StatsPeriods.DAY_MS, reads.single())
  }

  @Test fun plusComparisonSaysWhenThereIsNotEnoughData() {
    // Mon 28 - Wed 30: 3 of 7 days, under half, so no claim is made
    val comparison = assertNotNull(presenter(Plan.PLUS).load(PeriodKind.WEEK, now).comparison)
    assertEquals(listOf("สัปดาห์ก่อน มีข้อมูล 7 จาก 7 วัน", "ข้อมูลยังไม่พอจะเทียบกับสัปดาห์ก่อน"), comparison)
  }

  @Test fun unmeasuredNumbersAreNotShown() {
    val unconfirmed = SyntheticMonth.places.map { it.copy(confirmed = false) }
    val lines = assertNotNull(presenter(Plan.PLUS, StatsDataInput(tz, unconfirmed, SyntheticMonth.visits(), emptyList())).load(PeriodKind.MONTH, now).lines)
    assertTrue(lines.none { "อยู่บ้าน" in it || "ที่ทำงาน" in it || "บ่อยที่สุด" in it }, lines.toString())
  }

  @Test fun emptyPeriodSaysSoAndComparesNothing() {
    val s = presenter(Plan.PLUS, StatsDataInput(tz, emptyList(), emptyList(), emptyList())).load(PeriodKind.YEAR, now)
    assertEquals(listOf(StatsTemplates.NO_DATA), s.lines)
    assertNull(s.comparison)
  }
}
