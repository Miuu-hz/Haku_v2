package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowStateBuilderTest {
  private val tz = 420
  private fun at(hhmm: String, day: String = "2026-10-29") = MemoryFormat.parseUtc("${day}T$hhmm:00.000Z")!! - tz * 60_000L

  private val home = PlaceRecord("home", 13.78, 100.54, "บ้าน", PlaceCategory.HOME, confirmed = true)
  private val work = PlaceRecord("work", 13.72, 100.53, "ที่ทำงาน", PlaceCategory.WORK, confirmed = true)
  private val unknown = EventPlace(13.65, 100.40) // no confirmed place nearby

  private val events = listOf(
    CaptureEvent("s-home", at("19:00", "2026-10-28"), CaptureSource.DwellStart, EventPlace(home.lat, home.lon)),
    CaptureEvent("e-home", at("08:00"), CaptureSource.DwellEnd, EventPlace(home.lat, home.lon)),
    CaptureEvent("s-work", at("08:30"), CaptureSource.DwellStart, EventPlace(work.lat, work.lon)),
    CaptureEvent("e-work", at("12:00"), CaptureSource.DwellEnd, EventPlace(work.lat, work.lon)),
    CaptureEvent("s-x", at("12:10"), CaptureSource.DwellStart, unknown),
    CaptureEvent("cal-1", at("09:00"), CaptureSource.CalendarEntry("ประชุม"), endUtcMs = at("10:00")),
    CaptureEvent("cal-2", at("15:00"), CaptureSource.CalendarEntry("หมอฟัน"), endUtcMs = at("16:00")),
    CaptureEvent("cal-3", at("17:00"), CaptureSource.CalendarEntry("  ")),
    CaptureEvent("cal-tomorrow", at("09:00", "2026-10-30"), CaptureSource.CalendarEntry("พรุ่งนี้")),
  )

  private fun build(now: Long) = NowStateBuilder(FakeEvents(events.filter { it.timeUtcMs <= now || it.source is CaptureSource.CalendarEntry }), FakePlaces(listOf(home, work)), tz).build(now)

  @Test fun `open stay at an unconfirmed place, appointments not ended, last place left today`() {
    val s = build(at("12:40"))
    assertEquals("สถานที่หนึ่ง", s.placeName)
    assertEquals(at("12:10"), s.sinceUtcMs)
    assertEquals(listOf("หมอฟัน", "นัดไม่มีชื่อ"), s.remaining.map { it.title })
    assertTrue(s.hasAppointmentsToday)
    assertEquals("ที่ทำงาน", s.lastLeftPlace)
    assertEquals(at("12:00"), s.lastLeftUtcMs)
    assertTrue(listOf("s-x", "e-work", "cal-2", "cal-3").all { it in s.sourceEventIds }, s.sourceEventIds.toString())
  }

  @Test fun `overnight stay at home is where the user is before leaving`() {
    val s = build(at("07:00"))
    assertEquals("บ้าน", s.placeName)
    assertEquals(at("19:00", "2026-10-28"), s.sinceUtcMs)
    assertNull(s.lastLeftPlace) // left nothing yet today
    assertEquals(listOf("ประชุม", "หมอฟัน", "นัดไม่มีชื่อ"), s.remaining.map { it.title })
  }

  @Test fun `between stays nothing is claimed about now`() {
    val s = build(at("08:15"))
    assertNull(s.placeName)
    assertEquals("บ้าน", s.lastLeftPlace)
  }

  @Test fun `after the last appointment none are left`() {
    val s = build(at("23:00"))
    assertTrue(s.remaining.isEmpty())
    assertTrue(s.hasAppointmentsToday)
  }
}
