package com.bbpillow.haku.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillTest {
  private val md = """
    ---
    name: add-appointment
    description: Add one appointment to the phone calendar
    title: เพิ่มนัด
    aliases: นัด, ปฏิทิน
    action: calendar.add
    slots: title=ชื่อนัด; when=วันและเวลา; place?=สถานที่
    ---
    กรอกชื่อนัดและเวลาจากข้อความของผู้ใช้ ถ้าไม่มีเวลาให้ถามก่อน
  """.trimIndent()

  private val appt = SkillFormat.parse(md, SkillSource.BUILT_IN)
  private val weather = SkillRecord("weather-today", "อากาศ", "Weather forecast for a city", "", listOf(SkillSlot("city", "เมือง")),
    "mcp.call", source = SkillSource.MCP, server = "weather.example")
  private val all = listOf(appt, weather)

  @Test fun parsesAndRoundTrips() {
    assertEquals("เพิ่มนัด", appt.title)
    assertEquals(listOf("นัด", "ปฏิทิน"), appt.aliases)
    assertEquals(listOf(true, true, false), appt.slots.map { it.required })
    assertEquals(appt, SkillFormat.parse(SkillFormat.write(appt), SkillSource.BUILT_IN))
  }

  @Test fun rejectsGallerySkillWithoutActionAndOversizedOnes() {
    assertFailsWith<IllegalArgumentException> { SkillFormat.parse("---\nname: x\ndescription: y\n---\nsteps") }
    val long = md.replace("กรอกชื่อนัด", "ก".repeat(SkillRecord.MAX_INSTRUCTION_CHARS))
    assertFailsWith<IllegalArgumentException> { SkillFormat.parse(long) }
    assertFailsWith<IllegalArgumentException> { SkillFormat.parse(md.replace("place?=สถานที่", "a=1; b=2")) }
  }

  @Test fun validateDropsUnknownKeysAndReportsMissing() {
    val ok = appt.validate(mapOf("title" to " ทำฟัน ", "when" to "พรุ่งนี้ 10:00", "evil" to "rm -rf"))
    assertEquals(SlotResult.Ok(mapOf("title" to "ทำฟัน", "when" to "พรุ่งนี้ 10:00")), ok)
    assertEquals(SlotResult.Missing(listOf("when")), appt.validate(mapOf("title" to "ทำฟัน", "when" to "  ")))
  }

  @Test fun mentionsResolveSkillsAliasesAndServices() {
    val m = assertIs<Mention.Skill>(MentionParser.parse("/นัด พรุ่งนี้ 10 โมง ทำฟัน", all))
    assertEquals("add-appointment", m.skill.name)
    assertEquals("พรุ่งนี้ 10 โมง ทำฟัน", m.rest)
    assertIs<Mention.Service>(MentionParser.parse("@weather.example เชียงใหม่", all))
    assertNull(MentionParser.parse("/weather-today x", all)) // an MCP tool is only reachable through @server
    assertNull(MentionParser.parse("ไม่มี mention", all))
    assertNull(MentionParser.parse("/นัด x", listOf(appt.copy(enabled = false))))
  }

  @Test fun suggestionsWhileTyping() {
    assertEquals(listOf(appt), MentionParser.suggest("/นั", all))
    assertEquals(listOf(appt), MentionParser.suggest("/", all))
    assertEquals(listOf(weather), MentionParser.suggest("@w", all))
    assertTrue(MentionParser.suggest("/นัด พรุ่งนี้", all).isEmpty())
  }
}
