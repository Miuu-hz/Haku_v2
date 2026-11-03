package com.bbpillow.haku.action

import com.bbpillow.haku.surface.chat.AppointmentProposal
import com.bbpillow.haku.surface.chat.AppointmentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarAddArgsTest {
  @Test
  fun roundTripWithoutAllDayDefaultsToFalse() {
    val proposal = AppointmentProposal(
      title = "นัดขูดหินปูน",
      startUtcMs = 1_761_883_200_000L,
      endUtcMs = 1_761_886_800_000L,
      dayLabel = "ศ 31 ต.ค.",
      timeLabel = "10:00–11:00",
      conflict = "ชนกับ “ประชุมทีมขาย” 09:30–10:30",
      status = AppointmentStatus.PENDING,
      allDay = false,
    )
    val request = CalendarAddArgs.toRequest(proposal, origin = ActionOrigin.CHAT_RULE)
    assertEquals(CalendarAddArgs.ACTION, request.action)
    assertEquals("นัดขูดหินปูน", request.title)
    assertEquals("ศ 31 ต.ค. · 10:00–11:00", request.detail)
    assertEquals(proposal.conflict, request.warning)
    assertFalse(request.arguments.containsKey(CalendarAddArgs.KEY_ALL_DAY))

    val roundTripped = CalendarAddArgs.toProposal(request)
    assertEquals(proposal, roundTripped)
    assertNotNull(roundTripped)
    assertFalse(roundTripped.allDay)

    val parsed = CalendarAddArgs.parse(request.arguments)
    assertEquals(CalendarAddInput("นัดขูดหินปูน", 1_761_883_200_000L, 1_761_886_800_000L, allDay = false), parsed)
  }

  @Test
  fun roundTripWithAllDayCarriesFlag() {
    val proposal = AppointmentProposal(
      title = "อบรม",
      startUtcMs = 1_761_868_800_000L,
      endUtcMs = 1_762_041_600_000L,
      dayLabel = "5–6 พ.ย.",
      timeLabel = "ทั้งวัน",
      conflict = null,
      status = AppointmentStatus.PENDING,
      allDay = true,
    )
    val request = CalendarAddArgs.toRequest(proposal, origin = ActionOrigin.CHAT_RULE)
    assertEquals(CalendarAddArgs.ACTION, request.action)
    assertEquals("อบรม", request.title)
    assertEquals("5–6 พ.ย. · ทั้งวัน", request.detail)
    assertEquals("true", request.arguments[CalendarAddArgs.KEY_ALL_DAY])

    val roundTripped = CalendarAddArgs.toProposal(request)
    assertEquals(proposal, roundTripped)
    assertNotNull(roundTripped)
    assertTrue(roundTripped.allDay)

    val parsed = CalendarAddArgs.parse(request.arguments)
    assertEquals(CalendarAddInput("อบรม", 1_761_868_800_000L, 1_762_041_600_000L, allDay = true), parsed)
  }

  @Test
  fun oldJsonWithoutAllDayFieldParsesAsFalse() {
    val oldArguments = mapOf(
      CalendarAddArgs.KEY_TITLE to "นัดพบแพทย์",
      CalendarAddArgs.KEY_START_UTC_MS to "1761883200000",
      CalendarAddArgs.KEY_END_UTC_MS to "1761886800000",
    )
    val parsed = CalendarAddArgs.parse(oldArguments)
    assertNotNull(parsed)
    assertEquals("นัดพบแพทย์", parsed.title)
    assertEquals(1761883200000L, parsed.startUtcMs)
    assertEquals(1761886800000L, parsed.endUtcMs)
    assertFalse(parsed.allDay)

    val oldRequest = ActionRequestInput(
      action = CalendarAddArgs.ACTION,
      arguments = oldArguments,
      title = "นัดพบแพทย์",
      detail = "ศ 31 ต.ค. · 10:00–11:00",
      origin = ActionOrigin.NOTIFICATION_RULE,
    )
    val proposal = CalendarAddArgs.toProposal(oldRequest)
    assertNotNull(proposal)
    assertEquals("นัดพบแพทย์", proposal.title)
    assertEquals("ศ 31 ต.ค.", proposal.dayLabel)
    assertEquals("10:00–11:00", proposal.timeLabel)
    assertFalse(proposal.allDay)
  }

  @Test
  fun invalidArgumentsReturnNull() {
    assertNull(CalendarAddArgs.parse(emptyMap()))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "   ", "startUtcMs" to "10", "endUtcMs" to "20")))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "นัด", "startUtcMs" to "bad", "endUtcMs" to "20")))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "นัด", "startUtcMs" to "50", "endUtcMs" to "20")))
  }
}
