package com.bbpillow.haku.surface.home

import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.PendingOfferRecord
import com.bbpillow.haku.action.PendingOfferRepository
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
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
import com.bbpillow.haku.task.TaskRecord
import com.bbpillow.haku.task.TaskRepository
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

private class FakePendingOffers(records: List<PendingOfferRecord> = emptyList()) : PendingOfferRepository {
  val rows = records.toMutableList()
  override fun active(nowUtcMs: Long): List<PendingOfferRecord> = rows.filter { it.expiresUtcMs > nowUtcMs }
  override fun find(id: String): PendingOfferRecord? = rows.firstOrNull { it.id == id }
  override fun put(record: PendingOfferRecord) {
    rows.removeAll { it.dedupeKey == record.dedupeKey }
    rows.add(record)
  }
  override fun delete(id: String) { rows.removeAll { it.id == id } }
  override fun deleteExpired(nowUtcMs: Long): Int {
    val before = rows.size
    rows.removeAll { it.expiresUtcMs <= nowUtcMs }
    return before - rows.size
  }
  override fun clear() { rows.clear() }
}

private class FakeTasks(records: List<TaskRecord> = emptyList()) : TaskRepository {
  val rows = records.toMutableList()
  override fun all(): List<TaskRecord> = rows.toList()
  override fun find(id: String): TaskRecord? = rows.firstOrNull { it.id == id }
  override fun put(record: TaskRecord) {
    rows.removeAll { it.id == record.id }
    rows.add(record)
  }
  override fun delete(id: String) { rows.removeAll { it.id == id } }
  override fun deleteDoneBefore(cutoffUtcMs: Long): Int {
    val before = rows.size
    rows.removeAll { it.doneUtcMs != null && it.doneUtcMs < cutoffUtcMs }
    return before - rows.size
  }
  override fun clear() { rows.clear() }
}

private fun offer(
  id: String,
  title: String,
  detail: String = "detail",
  appName: String? = null,
  warning: String? = null,
  expiresUtcMs: Long = Long.MAX_VALUE,
) = PendingOfferRecord(
  id = id,
  dedupeKey = id,
  title = title,
  detail = detail,
  startUtcMs = 0L,
  endUtcMs = expiresUtcMs,
  allDay = false,
  origin = ActionOrigin.CHAT_RULE,
  appName = appName,
  warning = warning,
  createdUtcMs = 0L,
  expiresUtcMs = expiresUtcMs,
)

private fun task(
  id: String,
  title: String = "task",
  dueUtcMs: Long? = null,
  important: Boolean = false,
  doneUtcMs: Long? = null,
) = TaskRecord(
  id = id,
  title = title,
  dueUtcMs = dueUtcMs,
  important = important,
  createdUtcMs = 0L,
  doneUtcMs = doneUtcMs,
)

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
    offers: PendingOfferRepository? = null,
    tasks: TaskRepository? = null,
  ): HomeBriefPresenter {
    val composer = DiaryComposer(events, FakePlaces(places), memory, FakeLedger())
    if (compose) composer.compose(DiaryComposeInput(tuesday, tz))
    val entitlements = object : Entitlements { override val plan = plan }
    return HomeBriefPresenter(composer, events, FakePlaces(places), tz, summaries, StatsSummarizer(entitlements), offers, tasks)
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
    assertEquals("เมื่อวานของคุณ", s.storyTitle)
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
    assertEquals("เมื่อวานของคุณ", s.storyTitle)
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

  private fun appt(id: String, start: Long, end: Long?, title: String = id) =
    CaptureEvent(id, start, CaptureSource.CalendarEntry(title), endUtcMs = end)

  private fun withAppointments(vararg appts: CaptureEvent) = FakeEvents(SyntheticWeek.events() + appts)

  @Test fun appointmentsAreInStartOrderWithTheNextOneMarked() {
    val events = withAppointments(
      appt("dentist", at(1, 14), at(1, 15), "  ทำฟัน "),
      appt("standup", at(1, 9), at(1, 10)),
      appt("dinner", at(1, 18), null, ""),
    )
    val today = assertNotNull(presenter(events = events, compose = false).load(at(1, 12)).appointments)
    assertEquals(listOf("standup", "ทำฟัน", "นัดไม่มีชื่อ"), today.rows.map { it.title })
    assertEquals(listOf("09:00–10:00", "14:00–15:00", "18:00"), today.rows.map { it.timeText })
    assertEquals(listOf(true, false, false), today.rows.map { it.isPast })
    assertEquals(listOf(false, true, false), today.rows.map { it.isNext })
    assertEquals(0, today.moreCount)
  }

  @Test fun anOngoingAppointmentIsTheNextOne() {
    val events = withAppointments(appt("a", at(1, 9), at(1, 10)), appt("b", at(1, 14), at(1, 15)))
    val rows = assertNotNull(presenter(events = events, compose = false).load(at(1, 14, 30)).appointments).rows
    assertEquals(listOf(true, false), rows.map { it.isPast })
    assertEquals(listOf(false, true), rows.map { it.isNext })
  }

  @Test fun eveningShowsTodaysPastAppointmentsWithNoNext() {
    val events = withAppointments(appt("a", at(1, 9), at(1, 10)), appt("b", at(1, 14), null))
    val rows = assertNotNull(presenter(events = events, compose = false).load(at(1, 20)).appointments).rows
    assertTrue(rows.all { it.isPast })
    assertTrue(rows.none { it.isNext })
  }

  @Test fun moreThanFourDropsPastOnesFirstAndCountsTheRest() {
    val events = withAppointments(*(8..13).map { h -> appt("h$h", at(1, h), at(1, h, 30)) }.toTypedArray())
    val today = assertNotNull(presenter(events = events, compose = false).load(at(1, 9, 45)).appointments)
    assertEquals(listOf("h10", "h11", "h12", "h13"), today.rows.map { it.title })
    assertTrue(today.rows.first().isNext)
    assertEquals(2, today.moreCount)
  }

  @Test fun noAppointmentsHidesTheCard() {
    assertNull(presenter().load(at(1, 12)).appointments)
  }

  @Test fun deletedAppointmentsDoNotShow() {
    val events = withAppointments(appt("a", at(1, 9), at(1, 10)), appt("b", at(1, 14), at(1, 15)))
    events.delete("a")
    assertEquals(listOf("b"), assertNotNull(presenter(events = events, compose = false).load(at(1, 8)).appointments).rows.map { it.title })
    events.delete("b")
    assertNull(presenter(events = events, compose = false).load(at(1, 8)).appointments)
  }

  @Test fun todayIsTheLocalDayNotTheUtcDay() {
    // tz +7: local 00:00 today is 17:00 UTC the day before
    val events = withAppointments(
      appt("yesterdayLate", at(0, 23, 59), null),
      appt("midnight", at(1, 0), at(1, 0, 30)),
      appt("lastMinute", at(1, 23, 59), null),
      appt("tomorrow", at(2, 0), null),
    )
    val rows = assertNotNull(presenter(events = events, compose = false).load(at(1, 12)).appointments).rows
    assertEquals(listOf("midnight", "lastMinute"), rows.map { it.title })
    assertEquals(listOf("00:00–00:30", "23:59"), rows.map { it.timeText })
  }

  @Test fun decisionsUseAppNameOrSelectedTextWording() {
    val o1 = offer("o1", "Meeting", "10:00", appName = "ChatApp")
    val o2 = offer("o2", "Lunch", "12:00", appName = null, warning = "conflict")
    val state = presenter(offers = FakePendingOffers(listOf(o1, o2))).load(at(1, 12))
    assertEquals(2, state.decisions.size)
    assertEquals("o1", state.decisions[0].id)
    assertEquals("เจอนัดจาก ChatApp", state.decisions[0].heading)
    assertEquals("Meeting · 10:00", state.decisions[0].detail)
    assertNull(state.decisions[0].warning)
    assertEquals("o2", state.decisions[1].id)
    assertEquals("เจอนัดจากข้อความที่เลือก", state.decisions[1].heading)
    assertEquals("Lunch · 12:00", state.decisions[1].detail)
    assertEquals("conflict", state.decisions[1].warning)
  }

  @Test fun expiredOfferIsDeletedAndNotShown() {
    val now = at(1, 12)
    val expired = offer("o1", "Old", expiresUtcMs = now - 1000L)
    val active = offer("o2", "Active", expiresUtcMs = now + 10_000L)
    val store = FakePendingOffers(listOf(expired, active))
    val state = presenter(offers = store).load(now)
    assertEquals(listOf("o2"), state.decisions.map { it.id })
    assertEquals(listOf("o2"), store.rows.map { it.id })
  }

  @Test fun taskCountsIgnoresDoneTasks() {
    val now = at(1, 12)
    val openUrgent = task("t1", dueUtcMs = now + 1000L, important = true)
    val openImportant = task("t2", dueUtcMs = null, important = true)
    val doneUrgent = task("t3", dueUtcMs = now + 1000L, important = true, doneUtcMs = now - 1000L)
    val doneSomeday = task("t4", dueUtcMs = null, important = false, doneUtcMs = now - 1000L)
    val state = presenter(tasks = FakeTasks(listOf(openUrgent, openImportant, doneUrgent, doneSomeday))).load(now)
    assertEquals(1, state.taskCounts.importantUrgent)
    assertEquals(1, state.taskCounts.important)
    assertEquals(0, state.taskCounts.urgent)
    assertEquals(0, state.taskCounts.someday)
    assertEquals(2, state.taskCounts.total)
  }

  @Test fun isDayOneIsFalseWithOneOpenTaskOrOneOffer() {
    val withTask = presenter(emptyList(), FakeEvents(emptyList()), compose = false, tasks = FakeTasks(listOf(task("t1")))).load(at(1, 20))
    assertFalse(withTask.isDayOne)

    val withOffer = presenter(emptyList(), FakeEvents(emptyList()), compose = false, offers = FakePendingOffers(listOf(offer("o1", "Sync")))).load(at(1, 20))
    assertFalse(withOffer.isDayOne)
  }

  @Test fun headlineShowsRemainingAppointmentsOrPeriodHeadline() {
    val events = withAppointments(appt("a", at(1, 9), at(1, 10)), appt("b", at(1, 14), at(1, 15)))
    val p = presenter(events = events, compose = false)
    assertEquals("วันนี้มี 2 นัด", p.load(at(1, 8)).headline)
    assertEquals("วันนี้เหลืออีก 1 นัด", p.load(at(1, 12)).headline)
    assertEquals("บ่ายนี้มีอะไรบ้าง", p.load(at(1, 16)).headline)
    assertEquals("บ่ายนี้มีอะไรบ้าง", presenter(compose = false).load(at(1, 12)).headline)
  }
}
