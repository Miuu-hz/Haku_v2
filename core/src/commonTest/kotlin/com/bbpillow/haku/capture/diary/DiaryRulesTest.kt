package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.language.ThaiDateFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiaryRulesTest {
  private val min = 60_000L

  @Test fun durationsAreRoundedAndSpokenLikeAPerson() {
    fun d(m: Long) = DiaryTemplates.duration(m * min)
    assertEquals("45 นาที", d(45))
    assertEquals("40 นาที", d(41))
    assertEquals("1 ชั่วโมง", d(58))
    assertEquals("1 ชั่วโมงครึ่ง", d(90))
    assertEquals("2 ชั่วโมง", d(120))
    assertEquals("2 ชั่วโมง 15 นาที", d(135))
    assertEquals("8 ชั่วโมง", d(481))
  }

  @Test fun particleIsSpacedAfterDigitsAndQuotesButJoinsThaiWords() {
    val at = com.bbpillow.haku.memory.MemoryFormat.parseUtc("2026-09-07T06:30:00.000Z")!! // 13:30 at UTC+7
    val episodes = listOf(
      DiaryEpisode.Calendar(at, "ประชุมทีมขาย", at + 60 * min, listOf("c1")),
      DiaryEpisode.Calendar(at + min, "ประชุมทีมขาย", null, listOf("c2")),
      DiaryEpisode.Arrival(at + 2 * min, PlaceRef(category = PlaceCategory.HOME), null, 40 * min, listOf("a1")),
    )
    val plain = DiaryRenderer.render("2026-09-07", 420, DiaryStyle(), episodes).lines
    val ka = DiaryRenderer.render("2026-09-07", 420, DiaryStyle(EndingParticle.KA), episodes).lines
    assertEquals(
      listOf("13:30 มีนัด “ประชุมทีมขาย” ถึง 14:30 ค่ะ", "13:31 มีนัด “ประชุมทีมขาย” ค่ะ", "13:32 ถึงบ้าน อยู่ราว 40 นาทีค่ะ"),
      ka.map { it.text },
    )
    assertEquals(plain.map { it.id }, ka.map { it.id }) // ids never depend on wording
    assertEquals("13:30 มีนัด “ประชุมทีมขาย” ถึง 14:30", plain[0].text)
  }

  @Test fun thaiDateHeader() {
    assertEquals("วันจันทร์ที่ 7 กันยายน พ.ศ. 2569", ThaiDateFormat.dayHeader("2026-09-07"))
    assertEquals("วันอาทิตย์ที่ 1 มกราคม พ.ศ. 2566", ThaiDateFormat.dayHeader("2023-01-01"))
    assertEquals("วันพฤหัสบดีที่ 29 กุมภาพันธ์ พ.ศ. 2567", ThaiDateFormat.dayHeader("2024-02-29"))
    assertEquals("พฤ", ThaiDateFormat.shortWeekday("2026-09-24"))
    assertEquals("อา", ThaiDateFormat.shortWeekday("2023-01-01"))
    assertEquals("23 ก.ย.", ThaiDateFormat.shortDate("2026-09-23"))
    assertEquals("1 ม.ค.", ThaiDateFormat.shortDate("2023-01-01"))
  }

  private val home = PlaceRecord("h", 1.0, 1.0, confirmed = true, category = PlaceCategory.HOME)
  private val other = PlaceRecord("o", 2.0, 2.0)

  private fun dwell(id: String, atMin: Long, start: Boolean) =
    CaptureEvent(id, atMin * min, if (start) CaptureSource.DwellStart else CaptureSource.DwellEnd, EventPlace(0.0, 0.0))

  @Test fun busyDayIsCappedKeepingAppointmentsAndLongStays() {
    val events = mutableListOf<CaptureEvent>()
    val visits = mutableListOf<PlaceVisitRecord>()
    for (i in 0 until 20) {
      val s = 60L + i * 40
      events += dwell("s$i", s, true)
      events += dwell("e$i", s + 20, false)
      visits += PlaceVisitRecord("o", s * min, (s + 20) * min)
    }
    events += dwell("ls", 1000, true)
    events += dwell("le", 1180, false)
    visits += PlaceVisitRecord("h", 1000 * min, 1180 * min)
    events += CaptureEvent("cal", 100 * min, CaptureSource.CalendarEntry("ประชุม"))
    val out = EpisodeBuilder.build(DiaryBuildInput(0L, 1440 * min, events, listOf(home, other), visits))
    assertEquals(DiaryThresholds.MAX_LINES, out.size)
    assertTrue(out.any { it is DiaryEpisode.Calendar })
    assertTrue(out.any { it is DiaryEpisode.Arrival && it.stayMs == 180 * min })
    assertEquals(out.sortedBy { it.timeUtcMs }, out)
  }

  @Test fun walkBetweenBuildingsIsNotATrip() {
    val events = listOf(dwell("a1", 0, true), dwell("a2", 60, false), dwell("b1", 65, true), dwell("b2", 130, false))
    val visits = listOf(PlaceVisitRecord("h", 0, 60 * min), PlaceVisitRecord("o", 65 * min, 130 * min))
    val out = EpisodeBuilder.build(DiaryBuildInput(-1440 * min, 1440 * min, events, listOf(home, other), visits))
    val arrival = out.filterIsInstance<DiaryEpisode.Arrival>().first { it.timeUtcMs == 65 * min }
    assertNull(arrival.travelMs)
    assertTrue(out.none { it is DiaryEpisode.Departure && it.timeUtcMs == 60 * min })
  }
}
