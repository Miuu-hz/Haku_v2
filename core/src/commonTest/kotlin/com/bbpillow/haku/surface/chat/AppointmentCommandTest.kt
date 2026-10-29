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
}
