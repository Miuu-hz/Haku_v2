package com.bbpillow.haku.skill

import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.CalendarAddArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkillActionsTest {
  private val now = 1_762_131_600_000L // 2025-11-03 01:00 UTC = 08:00 ICT, a Monday
  private val tz = 7 * 60

  @Test fun appointmentSkillBecomesCalendarCardWithParsedTime() {
    val call = SkillActions.prepare(BuiltInSkills.ADD_APPOINTMENT, mapOf("title" to "ทำฟัน", "when" to "พรุ่งนี้ 10 โมง"), now, tz)
    val req = assertIs<SkillCall.Propose>(call).request
    assertEquals(CalendarAddArgs.ACTION, req.action)
    assertEquals("ทำฟัน", req.title)
    assertEquals(ActionOrigin.SKILL, req.origin)
    assertTrue(req.detail.contains("10:00"), req.detail)
  }

  @Test fun missingOrUnparsableTimeAsksTheUser() {
    assertIs<SkillCall.AskUser>(SkillActions.prepare(BuiltInSkills.ADD_APPOINTMENT, mapOf("title" to "ทำฟัน"), now, tz))
    assertIs<SkillCall.AskUser>(SkillActions.prepare(BuiltInSkills.ADD_APPOINTMENT, mapOf("title" to "ทำฟัน", "when" to "เร็วๆ นี้"), now, tz))
  }

  @Test fun mcpCallNamesServerAndWarnsBeforeLeavingThePhone() {
    val tool = SkillRecord("forecast", "พยากรณ์อากาศ", "Weather", "", listOf(SkillSlot("city", "เมือง")), SkillActions.MCP_ACTION,
      source = SkillSource.MCP, server = "weather.example")
    val req = assertIs<SkillCall.Propose>(SkillActions.prepare(tool, mapOf("city" to "เชียงใหม่", "x" to "y"), now, tz)).request
    assertEquals(mapOf("city" to "เชียงใหม่", SkillActions.KEY_SERVER to "weather.example", SkillActions.KEY_TOOL to "forecast"), req.arguments)
    assertEquals(ActionOrigin.MCP, req.origin)
    assertTrue(req.warning!!.contains("weather.example"))
  }
}
