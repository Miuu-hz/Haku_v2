package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.capture.SyntheticMonth
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TZ = SyntheticWeek.TZ_OFFSET_MIN
private const val MIN = SyntheticWeek.MIN_MS
private const val HOUR = 60 * MIN

private fun entitled(p: Plan) = object : Entitlements { override val plan = p }

private fun monthData(events: List<CaptureEvent> = emptyList(), fromDay: Int = SyntheticMonth.FIRST_DAY) =
  StatsDataInput(TZ, SyntheticMonth.places, SyntheticMonth.visits(fromDay), events)

private fun ready(r: StatsResult) = (r as StatsResult.Ready).summary

internal class StatsPeriodsTest {
  @Test fun weekRunsMondayToSunday() {
    val p = StatsPeriods.of(PeriodKind.WEEK, "2026-09-10", TZ) // a Thursday
    assertEquals("2026-09-07", p.startDay)
    assertEquals("2026-09-14", p.endDay)
    assertEquals(7, p.dayCount)
    assertEquals(SyntheticWeek.at(0, 0), p.fromUtcMs)
  }

  @Test fun monthAndYearBoundsIncludingDecember() {
    assertEquals(30, StatsPeriods.of(PeriodKind.MONTH, "2026-09-15", TZ).dayCount)
    assertEquals(28, StatsPeriods.of(PeriodKind.MONTH, "2026-02-01", TZ).dayCount)
    assertEquals("2027-01-01", StatsPeriods.of(PeriodKind.MONTH, "2026-12-31", TZ).endDay)
    assertEquals(365, StatsPeriods.of(PeriodKind.YEAR, "2026-06-01", TZ).dayCount)
    assertEquals(366, StatsPeriods.of(PeriodKind.YEAR, "2028-06-01", TZ).dayCount)
  }

  @Test fun previousPeriodIsAdjacent() {
    val m = StatsPeriods.of(PeriodKind.MONTH, "2026-01-20", TZ)
    val prev = StatsPeriods.previous(m, TZ)
    assertEquals("2025-12-01", prev.startDay)
    assertEquals(m.startDay, prev.endDay)
    assertEquals("2026-08-31", StatsPeriods.previous(StatsPeriods.of(PeriodKind.WEEK, "2026-09-07", TZ), TZ).startDay)
  }

  @Test fun badDayIsRejected() {
    assertTrue(runCatching { StatsPeriods.of(PeriodKind.WEEK, "2026-13-40", TZ) }.isFailure)
  }
}

internal class StatsCalculatorTest {
  private fun stats(kind: PeriodKind, day: String, data: StatsDataInput = monthData()) =
    StatsCalculator.compute(PeriodStatsInput(StatsPeriods.of(kind, day, TZ), TZ, data.places, data.visits, data.events))

  @Test fun septemberNumbersMatchTheSyntheticData() {
    val s = stats(PeriodKind.MONTH, "2026-09-15")
    assertEquals(30, s.coveredDays)
    assertEquals(30 * 24 * HOUR - (22 * 125 + 4 * 60) * MIN, s.recordedMs) // minus the unrecorded trip gaps
    assertEquals(22 * 8 * HOUR, s.workMs) // 22 weekdays, 3 h + 5 h
    assertEquals(4, s.distinctPlaces)
    assertEquals(22, s.placeUses.first { it.placeId == "food" }.visits)
    assertEquals(4, s.placeUses.first { it.placeId == "cafe" }.visits)
    assertEquals(44, s.placeUses.first().visits) // work, two stays a day
    assertEquals("work", s.placeUses.first().placeId)
    assertTrue(s.placeUses.none { it.placeId == "home" })
  }

  @Test fun newPlacesCountsOnlyPlacesFirstSeenInThePeriod() {
    assertEquals(1, stats(PeriodKind.MONTH, "2026-09-15").newPlaces) // food first appears in September
  }

  @Test fun newPlacesIsNullWithoutHistory() {
    assertNull(stats(PeriodKind.MONTH, "2026-09-15", monthData(fromDay = SyntheticMonth.SEP_FIRST_DAY)).newPlaces)
  }

  @Test fun timeAtHomeWorkAndOtherAddUp() {
    val s = stats(PeriodKind.WEEK, "2026-09-09")
    assertEquals(s.recordedMs, s.homeMs!! + s.workMs!! + s.otherMs!!)
    assertEquals(5 * 8 * HOUR, s.workMs)
    assertEquals(5 * 40 * MIN + 3 * HOUR, s.otherMs)
  }

  @Test fun homeWorkAndOtherAreNullUntilConfirmed() {
    val data = monthData().copy(places = SyntheticMonth.places.map { it.copy(confirmed = false) })
    val s = stats(PeriodKind.WEEK, "2026-09-09", data)
    assertNull(s.homeMs)
    assertNull(s.workMs)
    assertNull(s.otherMs)
    assertNull(s.awayFromHomeMs)
  }

  @Test fun travelCountsOnlyTripSizedGaps() {
    // per Sep weekday: home->work 45, work->food 10, food->work 10, work->home 60 min; Saturday: 30 + 30 min
    assertEquals((5 * (45 + 10 + 10 + 60) + 60) * MIN, stats(PeriodKind.WEEK, "2026-09-09").travelMs)
  }

  @Test fun appointmentsCountCalendarEntriesInThePeriodOnly() {
    val events = listOf(
      CaptureEvent("c1", SyntheticWeek.at(2, 15), CaptureSource.CalendarEntry("หมอฟัน")),
      CaptureEvent("c2", SyntheticWeek.at(8, 15), CaptureSource.CalendarEntry("อื่น")),
      CaptureEvent("d", SyntheticWeek.at(2, 9), CaptureSource.DwellStart),
    )
    assertEquals(1, stats(PeriodKind.WEEK, "2026-09-09", monthData(events)).appointments)
  }

  @Test fun emptyDataIsZeroNotCrash() {
    val s = stats(PeriodKind.WEEK, "2026-09-09", StatsDataInput(TZ, emptyList(), emptyList(), emptyList()))
    assertEquals(0, s.coveredDays)
    assertEquals(0L, s.recordedMs)
    assertTrue(s.placeUses.isEmpty())
    assertTrue(StatsCalculator.insights(s).isEmpty())
  }

  @Test fun staysAcrossLocalMidnightAreCut() {
    val data = StatsDataInput(TZ, SyntheticMonth.places, listOf(PlaceVisitRecord("home", SyntheticWeek.at(0, 22), SyntheticWeek.at(1, 2))), emptyList())
    val s = stats(PeriodKind.WEEK, "2026-09-09", data)
    assertEquals(2, s.coveredDays)
    assertEquals(4 * HOUR, s.recordedMs)
  }

  @Test fun busiestWeekdayIsSaturdayWithEvidence() {
    val top = StatsCalculator.insights(stats(PeriodKind.MONTH, "2026-09-15")).filterIsInstance<StatsInsight.BusiestWeekday>().single()
    assertEquals(5, top.weekday)
    assertEquals(4, top.occurrences)
    assertEquals(3 * HOUR, top.avgOtherMs)
    assertEquals(40 * MIN, top.runnerUpAvgMs)
  }

  @Test fun noWeekdayClaimFromASingleWeek() {
    assertTrue(StatsCalculator.insights(stats(PeriodKind.WEEK, "2026-09-09")).isEmpty())
  }

  @Test fun comparisonFindsMoreTimeAwayAndMoreRiceShopVisits() {
    val claims = StatsCalculator.compare(stats(PeriodKind.MONTH, "2026-09-15"), stats(PeriodKind.MONTH, "2026-08-15"))
    val away = claims.filterIsInstance<StatsInsight.AwayFromHomeChanged>().single()
    assertTrue(away.currentAvgMs > away.previousAvgMs)
    assertEquals(30, away.currentDays)
    assertEquals(31, away.previousDays)
    val place = claims.filterIsInstance<StatsInsight.PlaceVisitsChanged>().single()
    assertEquals("food", place.place.placeId)
    assertEquals(22, place.current)
    assertEquals(0, place.previous)
  }

  @Test fun noComparisonWhenThePreviousPeriodIsMostlyUnrecorded() {
    val data = monthData(fromDay = -10) // only the last 10 days of August exist
    val aug = stats(PeriodKind.MONTH, "2026-08-15", data)
    assertTrue(!aug.isReliable)
    assertTrue(StatsCalculator.compare(stats(PeriodKind.MONTH, "2026-09-15", data), aug).isEmpty())
  }

  @Test fun unnamedPlacesAreNeverNamedInClaims() {
    val data = monthData().copy(places = SyntheticMonth.places.map { if (it.id == "food") it.copy(label = null, confirmed = false) else it })
    val claims = StatsCalculator.compare(stats(PeriodKind.MONTH, "2026-09-15", data), stats(PeriodKind.MONTH, "2026-08-15", data))
    assertTrue(claims.none { it is StatsInsight.PlaceVisitsChanged })
  }
}

internal class StatsSummarizerTest {
  private val now = SyntheticWeek.at(3, 12)
  private fun weekInput() = StatsSummaryInput(PeriodKind.WEEK, "2026-09-10", now)
  private val monthInput = StatsSummaryInput(PeriodKind.MONTH, "2026-09-30", SyntheticWeek.at(23, 20))

  @Test fun freeSeesThisWeekOnlyWithoutComparison() {
    val out = ready(StatsSummarizer(entitled(Plan.FREE)).summarize(weekInput(), monthData()))
    assertNull(out.previous)
    assertTrue(out.insights.isEmpty())
    assertEquals("สัปดาห์นี้ มีข้อมูล 7 จาก 7 วัน", out.lines.first())
  }

  @Test fun freeIsLockedOutOfMonthAndYear() {
    val s = StatsSummarizer(entitled(Plan.FREE))
    assertEquals(StatsResult.Locked(StatsFeature.MONTH_SUMMARY), s.summarize(monthInput, monthData()))
    assertEquals(StatsResult.Locked(StatsFeature.YEAR_SUMMARY), s.summarize(monthInput.copy(kind = PeriodKind.YEAR), monthData()))
  }

  @Test fun plusGetsMonthComparisonAndPattern() {
    val out = ready(StatsSummarizer(entitled(Plan.PLUS)).summarize(monthInput, monthData()))
    assertNotNull(out.previous)
    assertEquals(3, out.insights.size) // busiest weekday, away change, rice shop
    assertTrue("วันเสาร์ออกไปข้างนอกบ่อยที่สุด เฉลี่ยวันละราว 3 ชั่วโมง (จาก 4 วันเสาร์ วันอื่นไม่เกินราว 40 นาที)" in out.lines)
    assertTrue("เดือนนี้ไป “ร้านข้าว” 22 ครั้ง เดือนก่อน 0 ครั้ง" in out.lines)
    assertTrue(out.lines.any { it.startsWith("เดือนนี้อยู่นอกบ้านเฉลี่ยวันละราว ") && "มากกว่าเดือนก่อน" in it })
  }

  @Test fun plusWeekWithoutPreviousWeekDataMakesNoComparison() {
    val events = SyntheticWeek.events()
    val rec = PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(events), tzOffsetMinutes = TZ))
    val out = ready(StatsSummarizer(entitled(Plan.PLUS)).summarize(weekInput(), StatsDataInput(TZ, rec.places, rec.visits, events)))
    assertEquals(1, out.previous!!.coveredDays) // only Sunday night before the week
    assertTrue(!out.previous!!.isReliable)
    assertTrue(out.insights.isEmpty())
    assertEquals(7, out.current.coveredDays)
    assertTrue(out.current.distinctPlaces >= 3)
    assertNull(out.current.homeMs) // nothing confirmed yet: no invented home time
  }

  @Test fun pastPeriodIsDatedAndHasNoNowRelativeWording() {
    val out = ready(StatsSummarizer(entitled(Plan.PLUS)).summarize(StatsSummaryInput(PeriodKind.MONTH, "2026-08-10", now), monthData()))
    assertEquals("2026-08-01 ถึง 2026-08-31 มีข้อมูล 31 จาก 31 วัน", out.lines.first())
    assertTrue(out.lines.none { "เดือนนี้" in it || "เดือนก่อน" in it })
  }

  @Test fun emptyPeriodSaysSo() {
    val out = ready(StatsSummarizer(entitled(Plan.PLUS)).summarize(monthInput, StatsDataInput(TZ, emptyList(), emptyList(), emptyList())))
    assertEquals(listOf(StatsTemplates.NO_DATA), out.lines)
  }

  @Test fun particleEndsEveryLine() {
    val out = ready(StatsSummarizer(entitled(Plan.PLUS)).summarize(monthInput.copy(style = DiaryStyle(EndingParticle.KRAP)), monthData()))
    assertTrue(out.lines.all { it.endsWith("ครับ") })
  }
}

internal class StatsEntitlementsTest {
  @Test fun freeGetsOnlyTheWeekPlusGetsAll() {
    assertTrue(entitled(Plan.FREE).allows(StatsFeature.WEEK_SUMMARY))
    for (f in StatsFeature.entries.filter { it != StatsFeature.WEEK_SUMMARY }) {
      assertTrue(!entitled(Plan.FREE).allows(f))
      assertTrue(entitled(Plan.PLUS).allows(f))
    }
  }
}
