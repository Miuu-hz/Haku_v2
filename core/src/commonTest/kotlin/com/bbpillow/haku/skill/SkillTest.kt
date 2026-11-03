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
  private val weather = SkillRecord(
    "weather-today", "อากาศ", "Weather forecast for a city", "", listOf(SkillSlot("city", "เมือง")),
    "mcp.call", source = SkillSource.MCP, server = "weather.example",
  )
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
    val builtInAppt = BuiltInSkills.ADD_APPOINTMENT
    val skills = listOf(builtInAppt, weather)

    val m = assertIs<Mention.Skill>(MentionParser.parse("/นัด พรุ่งนี้ 10 โมง ทำฟัน", skills))
    assertEquals("add-appointment", m.skill.name)
    assertEquals("พรุ่งนี้ 10 โมง ทำฟัน", m.rest)

    val atSkill = assertIs<Mention.Skill>(MentionParser.parse("@ปฏิทิน พรุ่งนี้ 10 โมง ทำฟัน", skills))
    assertEquals("add-appointment", atSkill.skill.name)
    assertEquals("พรุ่งนี้ 10 โมง ทำฟัน", atSkill.rest)

    val atService = assertIs<Mention.Service>(MentionParser.parse("@weather.example เชียงใหม่", skills))
    assertEquals("weather.example", atService.server)
    assertEquals(listOf(weather), atService.tools)
    assertEquals("เชียงใหม่", atService.rest)

    assertEquals(Mention.NotFound('/', "weather-today", "x"), MentionParser.parse("/weather-today x", skills)) // an MCP tool is only reachable through @server
    assertNull(MentionParser.parse("ไม่มี mention", skills))
    assertEquals(Mention.NotFound('/', "นัด", "x"), MentionParser.parse("/นัด x", listOf(builtInAppt.copy(enabled = false))))
    assertEquals(Mention.NotFound('@', "nosuch", "x"), MentionParser.parse("@nosuch x", skills))
    assertNull(MentionParser.parse("/ x", skills))
    assertNull(MentionParser.parse("@", skills))
  }

  @Test fun optionsPopupListsLocalAndServiceRows() {
    val appt = BuiltInSkills.ADD_APPOINTMENT
    val skills = listOf(appt, weather)

    // @ with empty partial lists local + service rows in order
    val atEmpty = MentionParser.options("@", skills)
    assertEquals(
      listOf(
        MentionOption("นัด", "เพิ่มนัด", MentionOption.Kind.LOCAL, listOf(appt)),
        MentionOption("weather.example", "weather.example", MentionOption.Kind.SERVICE, listOf(weather)),
      ),
      atEmpty,
    )

    // prefix "ปฏ" finds the calendar skill by alias
    assertEquals(
      listOf(MentionOption("นัด", "เพิ่มนัด", MentionOption.Kind.LOCAL, listOf(appt))),
      MentionParser.options("@ปฏ", skills),
    )

    // prefix on server host and on serviceNames label
    assertEquals(
      listOf(MentionOption("weather.example", "weather.example", MentionOption.Kind.SERVICE, listOf(weather))),
      MentionParser.options("@wea", skills),
    )
    assertEquals(
      listOf(MentionOption("weather.example", "พยากรณ์อากาศ", MentionOption.Kind.SERVICE, listOf(weather))),
      MentionParser.options("@พยา", skills, mapOf("weather.example" to "พยากรณ์อากาศ")),
    )

    // disabled skill hidden
    val disabledAppt = appt.copy(enabled = false)
    val disabledWeather = weather.copy(enabled = false)
    assertEquals(
      listOf(MentionOption("weather.example", "weather.example", MentionOption.Kind.SERVICE, listOf(weather))),
      MentionParser.options("@", listOf(disabledAppt, weather)),
    )
    assertEquals(
      listOf(MentionOption("นัด", "เพิ่มนัด", MentionOption.Kind.LOCAL, listOf(appt))),
      MentionParser.options("@", listOf(appt, disabledWeather)),
    )
    assertTrue(MentionParser.options("@", listOf(disabledAppt, disabledWeather)).isEmpty())

    // / lists local only
    assertEquals(
      listOf(MentionOption("นัด", "เพิ่มนัด", MentionOption.Kind.LOCAL, listOf(appt))),
      MentionParser.options("/", skills),
    )

    // space -> empty
    assertTrue(MentionParser.options("@ ", skills).isEmpty())
    assertTrue(MentionParser.options("/ ", skills).isEmpty())
    assertTrue(MentionParser.options("@นัด พรุ่งนี้", skills).isEmpty())
    assertTrue(MentionParser.options(" @", skills).isEmpty())
    assertTrue(MentionParser.options("", skills).isEmpty())

    // multiple tools for same server grouped into one service row
    val weather2 = SkillRecord(
      "forecast-tomorrow", "พรุ่งนี้", "Tomorrow", "", emptyList(),
      "mcp.call", source = SkillSource.MCP, server = "weather.example",
    )
    assertEquals(
      listOf(
        MentionOption("นัด", "เพิ่มนัด", MentionOption.Kind.LOCAL, listOf(appt)),
        MentionOption("weather.example", "weather.example", MentionOption.Kind.SERVICE, listOf(weather, weather2)),
      ),
      MentionParser.options("@", listOf(appt, weather, weather2)),
    )
  }
}
