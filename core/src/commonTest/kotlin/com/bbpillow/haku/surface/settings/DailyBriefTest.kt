package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.surface.home.AppointmentRow
import com.bbpillow.haku.surface.home.BriefPeriod
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.home.TodayAppointments
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class DailyBriefTest {
  private val bangkok = 7 * 60
  private fun utc(s: String) = requireNotNull(MemoryFormat.parseUtc(s))
  private val morning = DailyBrief.MORNING_DEFAULT_MINUTE
  private val evening = DailyBrief.EVENING_DEFAULT_MINUTE

  @Test fun nextFireIsTodayWhenTheTimeIsStillAhead() {
    // 07:00 Bangkok → 07:30 the same local day
    assertEquals(utc("2026-10-29T00:30:00.000Z"), DailyBrief.nextFireUtc(utc("2026-10-29T00:00:00.000Z"), bangkok, morning))
    // 01:00 Bangkok (still 28th in UTC) → 07:30 on the 29th
    assertEquals(utc("2026-10-29T00:30:00.000Z"), DailyBrief.nextFireUtc(utc("2026-10-28T18:00:00.000Z"), bangkok, morning))
  }

  @Test fun pastOrExactlyNowGoesToTomorrow() {
    assertEquals(utc("2026-10-30T00:30:00.000Z"), DailyBrief.nextFireUtc(utc("2026-10-29T01:00:00.000Z"), bangkok, morning))
    assertEquals(utc("2026-10-30T00:30:00.000Z"), DailyBrief.nextFireUtc(utc("2026-10-29T00:30:00.000Z"), bangkok, morning))
    // 23:00 Bangkok → 21:00 tomorrow
    assertEquals(utc("2026-10-30T14:00:00.000Z"), DailyBrief.nextFireUtc(utc("2026-10-29T16:00:00.000Z"), bangkok, evening))
  }

  @Test fun noDstSoEveryFireIsExactly24HoursApart() {
    var t = utc("2026-01-01T00:00:00.000Z")
    repeat(366) {
      val next = DailyBrief.nextFireUtc(t, bangkok, morning)
      if (it > 0) assertEquals(86_400_000L, next - t)
      t = next
    }
  }

  @Test fun lateRunIsDroppedOnTimeRunIsKept() {
    assertTrue(DailyBrief.isOnTime(utc("2026-10-29T00:40:00.000Z"), bangkok, morning))
    assertFalse(DailyBrief.isOnTime(utc("2026-10-29T06:00:00.000Z"), bangkok, morning)) // 13:00, phone was off
  }

  private fun home(lines: List<String>, summary: String? = null, appointments: TodayAppointments? = null) = HomeBriefUiState(
    period = BriefPeriod.MORNING, greeting = "อรุณสวัสดิ์", headline = "", storyTitle = "เมื่อวาน", storyDay = "2026-10-28",
    storyHeader = "", storyLines = lines, storyLineCount = lines.size, stats = null, storySummary = summary, appointments = appointments,
  )

  @Test fun nothingToSayMeansNoNotification() {
    assertNull(DailyBrief.compose(BriefKind.MORNING, home(emptyList()), DiaryStyle()))
    assertNull(DailyBrief.compose(BriefKind.EVENING, home(emptyList()), DiaryStyle()))
    // appointments are a morning thing; an evening with no diary line still says nothing
    val appts = TodayAppointments(listOf(AppointmentRow("ประชุม", "09:00", isPast = false, isNext = true)), 0)
    assertNull(DailyBrief.compose(BriefKind.EVENING, home(emptyList(), appointments = appts), DiaryStyle()))
  }

  @Test fun morningTellsTodaysAppointmentsAndYesterdaysSummary() {
    val appts = TodayAppointments(listOf(AppointmentRow("ประชุม", "09:00", isPast = false, isNext = true)), 2)
    val out = assertNotNull(DailyBrief.compose(BriefKind.MORNING, home(listOf("08:30 ออกจากบ้าน"), "เมื่อวานไปทำงาน", appts), DiaryStyle()))
    assertEquals("อรุณสวัสดิ์", out.title)
    assertEquals("วันนี้มีนัด 3 นัด ถัดไป 09:00 ประชุม\nเมื่อวาน: เมื่อวานไปทำงาน", out.text)
  }

  @Test fun eveningFallsBackToTheTemplateAndInvites() {
    val out = assertNotNull(DailyBrief.compose(BriefKind.EVENING, home(listOf("08:30 ออกจากบ้าน")), DiaryStyle()))
    assertTrue(out.text.contains("08:30 ออกจากบ้าน"))
    assertTrue(out.text.endsWith(DailyBrief.EVENING_INVITE))
  }

  @Test fun profileWithoutBriefKeysParsesAsOffAtDefaultTimes() {
    val fs = FakeFileSystem()
    val file = "/p/profile.txt".toPath()
    fs.createDirectories(file.parent!!)
    fs.write(file) { writeUtf8("particle=KA\naddress=KHUN\nnickname=\nmodel=E2B\nlock=true\nonboarded=true\n") }
    val loaded = ProfileStore(fs, file).load()
    assertTrue(loaded.onboarded && loaded.lockEnabled)
    assertFalse(loaded.morningBriefOn || loaded.eveningBriefOn)
    assertEquals(morning, loaded.morningBriefMinute)
    assertEquals(evening, loaded.eveningBriefMinute)
  }

  @Test fun briefFieldsRoundTripAndBadTimeFallsBack() {
    val fs = FakeFileSystem()
    val store = ProfileStore(fs, "/p/profile.txt".toPath())
    val saved = ProfileRecord(onboarded = true, morningBriefOn = true, morningBriefMinute = 6 * 60 + 5, eveningBriefMinute = 22 * 60)
    store.save(saved)
    assertEquals(saved, store.load())
    fs.write("/p/profile.txt".toPath()) { writeUtf8("brief_morning=true\nbrief_morning_at=25:99\nbrief_evening_at=7:3\n") }
    val bad = store.load()
    assertTrue(bad.morningBriefOn)
    assertEquals(morning, bad.morningBriefMinute)
    assertEquals(evening, bad.eveningBriefMinute)
  }

  @Test fun settingsPresenterSavesToggleAndTime() {
    val store = ProfileStore(FakeFileSystem(), "/p/profile.txt".toPath())
    val presenter = ModelManagerPresenter(store)
    assertFalse(presenter.loadSettings(emptyList()).morningBriefOn) // never pre-ticked
    presenter.setBrief(BriefKind.EVENING, true, emptyList())
    val ui = presenter.setBriefTime(BriefKind.EVENING, 20 * 60 + 15, emptyList())
    assertTrue(ui.eveningBriefOn)
    assertEquals("20:15", ui.eveningBriefTime)
    assertFalse(ui.morningBriefOn)
  }
}
