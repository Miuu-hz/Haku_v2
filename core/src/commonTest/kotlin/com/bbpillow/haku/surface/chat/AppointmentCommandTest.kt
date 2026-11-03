package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.thinking.appointment.AppointmentDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppointmentCommandTest {
  private val tzOffsetMinutes = 7 * 60 // Bangkok UTC+7
  private val nowUtcMs = requireNotNull(MemoryFormat.parseUtc("2026-10-30T09:00:00.000Z"))

  @Test
  fun appointmentSentenceProducesProposalWithRightLabels() {
    val command = AppointmentCommand()
    val result = command.tryPropose("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    assertEquals("เพิ่มนัดนี้ลงปฏิทินไหม", result.text)
    assertEquals(ChatAnswerSource.RETRIEVAL, result.source)
    assertEquals(emptyList(), result.citations)

    val proposal = result.appointment
    assertNotNull(proposal)
    assertTrue(proposal.title.contains("ขูดหินปูน"))
    val expectedDay = MemoryFormat.dayOf(proposal.startUtcMs + tzOffsetMinutes * 60_000L)
    val expectedDayLabel = "${ThaiDateFormat.shortWeekday(expectedDay)} ${ThaiDateFormat.shortDate(expectedDay)}"
    assertEquals(expectedDayLabel, proposal.dayLabel)
    assertEquals("10:00–11:00", proposal.timeLabel)
    assertNull(proposal.conflict)
    assertEquals(AppointmentStatus.PENDING, proposal.status)
  }

  @Test
  fun ordinaryQuestionReturnsNull() {
    val command = AppointmentCommand()
    val result = command.tryPropose("เมื่อวานไปไหนมา", nowUtcMs, tzOffsetMinutes)
    assertNull(result)
  }

  @Test
  fun formatsLabelsCorrectlyFromDraft() {
    // 2026-10-31 is Saturday ("ส 31 ต.ค.")
    val startUtc = requireNotNull(MemoryFormat.parseUtc("2026-10-31T03:00:00.000Z")) // 10:00 Bangkok
    val endUtc = requireNotNull(MemoryFormat.parseUtc("2026-10-31T04:00:00.000Z"))   // 11:00 Bangkok
    val command = AppointmentCommand { _, _, _ ->
      AppointmentDraft("ขูดหินปูน", startUtc, endUtc)
    }
    val result = command.tryPropose("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    val proposal = result.appointment
    assertNotNull(proposal)
    assertEquals("ขูดหินปูน", proposal.title)
    assertEquals("ส 31 ต.ค.", proposal.dayLabel)
    assertEquals("10:00–11:00", proposal.timeLabel)
    assertEquals(AppointmentStatus.PENDING, proposal.status)
  }

  @Test
  fun formatsFridayLabelCorrectly() {
    // 2025-10-31 was Friday ("ศ 31 ต.ค.")
    val startUtc = requireNotNull(MemoryFormat.parseUtc("2025-10-31T03:00:00.000Z")) // 10:00 Bangkok
    val endUtc = requireNotNull(MemoryFormat.parseUtc("2025-10-31T04:00:00.000Z"))   // 11:00 Bangkok
    val command = AppointmentCommand { _, _, _ ->
      AppointmentDraft("ประชุม", startUtc, endUtc)
    }
    val result = command.tryPropose("มีนัดประชุม", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    val proposal = result.appointment
    assertNotNull(proposal)
    assertEquals("ประชุม", proposal.title)
    assertEquals("ศ 31 ต.ค.", proposal.dayLabel)
    assertEquals("10:00–11:00", proposal.timeLabel)
  }

  @Test
  fun singleDayAllDayAppointmentHasThangWanTimeLabelAndDateLabel() {
    // 2026-11-05 is Thursday ("พฤ 5 พ.ย.")
    val startUtc = requireNotNull(MemoryFormat.parseUtc("2026-11-04T17:00:00.000Z")) // 00:00 Bangkok on 5 Nov
    val endUtc = requireNotNull(MemoryFormat.parseUtc("2026-11-05T17:00:00.000Z"))   // 00:00 Bangkok on 6 Nov
    val command = AppointmentCommand { _, _, _ ->
      AppointmentDraft("ตรวจสุขภาพ", startUtc, endUtc, allDay = true)
    }
    val result = command.tryPropose("มีนัดตรวจสุขภาพ วันที่ 5 พฤศจิกายน", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    val proposal = result.appointment
    assertNotNull(proposal)
    assertEquals("ตรวจสุขภาพ", proposal.title)
    assertEquals("พฤ 5 พ.ย.", proposal.dayLabel)
    assertEquals("ทั้งวัน", proposal.timeLabel)
    assertTrue(proposal.allDay)
  }

  @Test
  fun rangeAllDayAppointmentWithinSameMonthFormatsRangeAndThangWan() {
    // 2026-11-05 to 2026-11-06 (end is 2026-11-07 00:00 local)
    val startUtc = requireNotNull(MemoryFormat.parseUtc("2026-11-04T17:00:00.000Z"))
    val endUtc = requireNotNull(MemoryFormat.parseUtc("2026-11-06T17:00:00.000Z"))
    val command = AppointmentCommand { _, _, _ ->
      AppointmentDraft("อบรม", startUtc, endUtc, allDay = true)
    }
    val result = command.tryPropose("มีนัดอบรม 5 - 6 พ.ย.", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    val proposal = result.appointment
    assertNotNull(proposal)
    assertEquals("อบรม", proposal.title)
    assertEquals("5–6 พ.ย.", proposal.dayLabel)
    assertEquals("ทั้งวัน", proposal.timeLabel)
    assertTrue(proposal.allDay)
  }

  @Test
  fun rangeAllDayAppointmentAcrossMonthsFormatsCrossMonthAndThangWan() {
    // 2026-10-31 to 2026-11-01 (end is 2026-11-02 00:00 local)
    val startUtc = requireNotNull(MemoryFormat.parseUtc("2026-10-30T17:00:00.000Z"))
    val endUtc = requireNotNull(MemoryFormat.parseUtc("2026-11-01T17:00:00.000Z"))
    val command = AppointmentCommand { _, _, _ ->
      AppointmentDraft("สัมมนา", startUtc, endUtc, allDay = true)
    }
    val result = command.tryPropose("มีนัดสัมมนา 31 ต.ค. - 1 พ.ย.", nowUtcMs, tzOffsetMinutes)
    assertNotNull(result)
    val proposal = result.appointment
    assertNotNull(proposal)
    assertEquals("สัมมนา", proposal.title)
    assertEquals("31 ต.ค.–1 พ.ย.", proposal.dayLabel)
    assertEquals("ทั้งวัน", proposal.timeLabel)
    assertTrue(proposal.allDay)
  }
}
