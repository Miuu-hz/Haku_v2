package com.bbpillow.haku.surface.home

import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.SyntheticWeek.at
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.thinking.summary.EveningSummaryRecord
import com.bbpillow.haku.thinking.summary.EveningSummaryRepository
import com.bbpillow.haku.thinking.summary.SummarySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private class MemSummaries(vararg records: EveningSummaryRecord) : EveningSummaryRepository {
  private val byDay = records.associateBy { it.day }.toMutableMap()
  override fun find(day: String) = byDay[day]
  override fun save(record: EveningSummaryRecord) { byDay[record.day] = record }
  override fun delete(day: String) = byDay.remove(day) != null
  override fun clear() = byDay.clear()
}

class HomeBriefPresenterTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val tuesday = "2026-09-08"
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }
  private val recognized = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), emptyList(), tz),
  )

  private fun presenter(
    places: List<PlaceRecord> = recognized.places,
    events: FakeEvents = FakeEvents(SyntheticWeek.events()),
    compose: Boolean = true,
    summaries: EveningSummaryRepository = MemSummaries(),
    plan: Plan = Plan.FREE,
  ): HomeBriefPresenter {
    val composer = DiaryComposer(events, FakePlaces(places), memory, FakeLedger())
    if (compose) composer.compose(DiaryComposeInput(tuesday, tz))
    val entitlements = object : Entitlements { override val plan = plan }
    return HomeBriefPresenter(composer, events, FakePlaces(places), tz, summaries, StatsSummarizer(entitlements))
  }

  private fun withConfirmedHome(): List<PlaceRecord> {
    val homeId = recognized.guesses.first { it.category == PlaceCategory.HOME }.placeId
    return recognized.places.map {
      if (it.id == homeId) it.copy(label = "บ้าน", category = PlaceCategory.HOME, confirmed = true) else it
    }
  }

  @Test fun eveningTellsTodaysStory() {
    val s = presenter().load(at(1, 20))
    assertEquals(BriefPeriod.EVENING, s.period)
    assertEquals("วันนี้เป็นยังไงบ้าง", s.headline)
    assertEquals("เรื่องของวันนี้", s.storyTitle)
    assertEquals(tuesday, s.storyDay)
    assertEquals(HomeBriefPresenter.STORY_LINES, s.storyLines.size)
    assertTrue(s.storyLines.first().startsWith("08:15"), s.storyLines.first())
    assertEquals(8, s.storyLineCount)
    assertFalse(s.isDayOne)
  }

  @Test fun morningLooksBackAtYesterday() {
    val s = presenter().load(at(2, 8))
    assertEquals(BriefPeriod.MORNING, s.period)
    assertEquals("เมื่อวาน", s.storyTitle)
    assertEquals(tuesday, s.storyDay)
    assertTrue(s.greeting.startsWith("อรุณสวัสดิ์"))
    assertEquals(8, s.storyLineCount)
  }

  @Test fun threePartsOfTheDayGiveThreeDifferentBriefs() {
    val p = presenter()
    val briefs = listOf(at(2, 8), at(2, 13), at(2, 19)).map { p.load(it) }
    assertEquals(listOf(BriefPeriod.MORNING, BriefPeriod.MIDDAY, BriefPeriod.EVENING), briefs.map { it.period })
    assertEquals(3, briefs.map { it.headline }.toSet().size)
    assertEquals(3, briefs.map { it.storyTitle }.toSet().size)
  }

  @Test fun beforeDawnStillShowsTheDayThatEnded() {
    val s = presenter().load(at(2, 1))
    assertEquals(BriefPeriod.EVENING, s.period)
    assertEquals(tuesday, s.storyDay)
    assertEquals("เมื่อวาน", s.storyTitle)
  }

  @Test fun greetingUsesTheChosenAddress() {
    val p = presenter()
    val nick = DiaryStyle(address = AddressForm.NICKNAME, nickname = "มิ้นท์")
    assertEquals("สวัสดีตอนเย็น มิ้นท์", p.load(at(1, 20), nick).greeting)
    assertEquals("สวัสดีตอนเย็น", p.load(at(1, 20), DiaryStyle()).greeting)
    assertEquals("สวัสดีตอนเย็น", p.load(at(1, 20), nick.copy(nickname = " ")).greeting)
  }

  @Test fun dayOneShowsTheEmptyStateWithoutNumbers() {
    val s = presenter(emptyList(), FakeEvents(emptyList())).load(at(1, 20))
    assertTrue(s.isDayOne)
    assertEquals("Haku กำลังเรียนรู้วันของคุณ", s.headline)
    assertTrue(s.storyLines.isEmpty())
    assertEquals(0, s.storyLineCount)
    assertNull(s.stats)
  }

  @Test fun withoutAConfirmedHomeThereIsNoOutOfHomeNumber() {
    val stats = assertNotNull(presenter().load(at(1, 20)).stats)
    assertEquals(3, stats.placeCount) // home, office, cafe; the 7 min store stop is not a stay
    assertNull(stats.outOfHomeFraction)
    assertNull(stats.outOfHomeMinutes)
    assertNull(stats.steps) // no step source exists: never invented
  }

  @Test fun outOfHomeIsMeasuredAgainstTheConfirmedHome() {
    val stats = assertNotNull(presenter(withConfirmedHome()).load(at(1, 20)).stats)
    // covered 00:00-18:00, home until 08:15 -> 9 h 45 min away
    assertEquals(585, stats.outOfHomeMinutes)
    assertEquals(585f / (18 * 60), assertNotNull(stats.outOfHomeFraction), 0.001f)
  }

  @Test fun staysWithoutADiaryAreNotDayOne() {
    val s = presenter(compose = false).load(at(1, 20))
    assertTrue(s.storyLines.isEmpty())
    assertFalse(s.isDayOne)
    assertNotNull(s.stats)
  }

  @Test fun storedSummaryReplacesTheDiaryLines() {
    val text = "วันนี้ออกจากบ้านตอนเช้า แล้วไปทำงานครับ"
    val s = presenter(summaries = MemSummaries(EveningSummaryRecord(tuesday, text, SummarySource.MODEL))).load(at(1, 20))
    assertEquals(text, s.storySummary)
    assertEquals(8, s.storyLineCount) // the source lines stay one tap away
  }

  @Test fun withoutASummaryTheDiaryLinesStay() {
    val s = presenter().load(at(1, 20))
    assertNull(s.storySummary)
    assertEquals(HomeBriefPresenter.STORY_LINES, s.storyLines.size)
  }

  @Test fun summaryWithoutAnyDiaryLineIsNotShown() {
    val stored = MemSummaries(EveningSummaryRecord(tuesday, "เรื่องที่ถูกลบไปแล้ว", SummarySource.TEMPLATE))
    assertNull(presenter(compose = false, summaries = stored).load(at(1, 20)).storySummary)
  }

  @Test fun freePlanGetsThisWeekWithoutComparison() {
    val card = assertNotNull(presenter().load(at(1, 20)).weekly)
    assertTrue(card.comparisonLocked)
    assertTrue(card.lines.first().startsWith("สัปดาห์นี้ มีข้อมูล 2 จาก 7 วัน"), card.lines.first())
    assertTrue(card.lines.none { "สัปดาห์ก่อน" in it })
  }

  @Test fun plusPlanUnlocksTheComparison() {
    assertFalse(assertNotNull(presenter(plan = Plan.PLUS).load(at(1, 20)).weekly).comparisonLocked)
  }

  @Test fun noWeeklyCardWithoutData() {
    assertNull(presenter(emptyList(), FakeEvents(emptyList())).load(at(1, 20)).weekly)
  }
}
