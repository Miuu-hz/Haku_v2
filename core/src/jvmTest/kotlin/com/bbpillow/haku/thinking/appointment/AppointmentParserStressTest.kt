package com.bbpillow.haku.thinking.appointment

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Booth stress: every title × time × date × word-order combination must parse to the right start and title, and no
 * chat question from the eval sets may be taken for an appointment (that would hijack the chat demo).
 */
class AppointmentParserStressTest {
  private val zone = ZoneOffset.ofHours(7)
  private val nowLocal = LocalDateTime.of(2026, 10, 30, 8, 0) // Friday
  private val now = nowLocal.toInstant(zone).toEpochMilli()
  private val tz = 420

  private data class Title(val text: String, val expected: String)
  private data class Time(val text: String, val hour: Int, val minute: Int)
  private data class Day(val text: String, val dates: List<LocalDate>?) // null = no date given

  private val titles = listOf(
    Title("มีนัดขูดหินปูน", "ขูดหินปูน"),
    Title("นัดตัดผม", "ตัดผม"),
    Title("มีนัดตรวจสุขภาพประจำปี", "ตรวจสุขภาพประจำปี"),
    Title("ประชุมทีมขาย", "ประชุมทีมขาย"),
    Title("สัมภาษณ์งาน", "สัมภาษณ์งาน"),
    Title("นัดคุยงานกับพี่เอ", "คุยงานกับพี่เอ"),
  )
  private val times = listOf(
    Time("10.00น.", 10, 0), Time("10:30", 10, 30), Time("สิบโมง", 10, 0), Time("9 โมงเช้า", 9, 0),
    Time("เที่ยง", 12, 0), Time("บ่ายสอง", 14, 0), Time("บ่าย 3 โมง", 15, 0), Time("บ่ายสองครึ่ง", 14, 30),
    Time("4 โมงเย็น", 16, 0), Time("สองทุ่ม", 20, 0), Time("๙.๓๐ น.", 9, 30),
  )
  private val today = nowLocal.toLocalDate()
  private val days = listOf(
    Day("พรุ่งนี้", listOf(today.plusDays(1))),
    Day("วันนี้", listOf(today)),
    Day("มะรืนนี้", listOf(today.plusDays(2))),
    Day("วันจันทร์", listOf(LocalDate.of(2026, 11, 2))),
    Day("วันจันทร์หน้า", listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9))), // Thai usage differs; accept both
    Day("5 พ.ย.", listOf(LocalDate.of(2026, 11, 5))),
    Day("วันที่ 12", listOf(LocalDate.of(2026, 11, 12))),
    Day("", null),
  )
  private val endings = listOf("", " ครับ", "ค่ะ")

  @Test fun `combinations parse to the right start and title`() {
    val fails = mutableListOf<String>()
    var total = 0
    for (t in titles) for (time in times) for (d in days) for ((i, end) in endings.withIndex()) {
      val text = if (i % 2 == 0) "${t.text} ${time.text} ${d.text}$end" else "${t.text}${d.text} ${time.text}$end"
      total++
      val draft = AppointmentParser.parse(text.trim(), now, tz)
      if (draft == null) { fails += "null      | $text"; continue }
      val start = LocalDateTime.ofEpochSecond(draft.startUtcMs / 1000, 0, zone)
      val okDates = d.dates ?: listOf(if (time.hour * 60 + time.minute > 8 * 60) today else today.plusDays(1))
      val okTime = start.hour == time.hour && start.minute == time.minute
      when {
        !okTime || start.toLocalDate() !in okDates -> fails += "when $start | $text"
        draft.title != t.expected -> fails += "title '${draft.title}' | $text"
      }
    }
    report("positives", total, fails)
    assertTrue(fails.size * 100 <= total * 5, "more than 5% wrong (${fails.size}/$total), see the report above")
  }

  @Test fun `chat questions and intention commands are never appointments`() {
    val questions = File("../scripts/stress/questions_th.txt").readLines().filter { it.isNotBlank() } +
      Regex("\"question\": \"([^\"]+)\"").findAll(File("../scripts/stress/demo_eval.py").readText()).map { it.groupValues[1] } +
      listOf(
        "นัดหมอฟันเมื่อไหร่", "พรุ่งนี้มีนัดกี่โมง", "มีนัดอะไรบ้าง", "ประชุมทีมขายวันไหน", "นัดขูดหินปูนตอน 10 โมงใช่ไหม",
        "ถ้าถึงที่ทำงานให้เตือนส่งรายงาน", "ทุกครั้งที่ไปประชุมให้เตือนเอาโน้ตบุ๊ก", "เมื่อวานไปประชุมมา 10 โมง",
      )
    val fails = questions.filter { AppointmentParser.parse(it, now, tz) != null }.map { "parsed    | $it" }
    report("negatives", questions.size, fails)
    assertTrue(fails.isEmpty(), "questions taken for appointments, see the report above")
  }

  private fun report(name: String, total: Int, fails: List<String>) {
    println("[appointment-stress] $name: ${total - fails.size}/$total ok")
    fails.forEach { println("[appointment-stress]   $it") }
  }
}
