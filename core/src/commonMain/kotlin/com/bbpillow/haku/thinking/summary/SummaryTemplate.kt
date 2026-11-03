package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.language.ThaiDateFormat

/** The deterministic fallback paragraph, built only from diary lines. Never blank, never invented. */
object SummaryTemplate {
  const val NO_DATA = "วันนี้ยังไม่มีบันทึกอะไรเลย ไว้พรุ่งนี้มาดูกันใหม่"
  private val connectors = listOf("จากนั้น", "ต่อมา", "หลังจากนั้น")

  fun noData(style: DiaryStyle) = style.particle.appendTo(NO_DATA)

  /** "วันจันทร์ที่ 7 ... เวลา 08:30 ออกจากบ้าน จากนั้นเวลา 09:10 ถึงที่ทำงาน ..." with the style's particle at the end. */
  fun paragraph(day: String, lines: List<String>, style: DiaryStyle): String {
    val body = lines.map { style.particle.stripFrom(it.trim()).trimEnd() }.filter { it.isNotEmpty() }
    if (body.isEmpty()) return noData(style)
    return style.particle.appendTo(sentence(ThaiDateFormat.dayHeader(day), body))
  }

  /** One part's fallback, no particle (the summary adds it once): "ช่วงบ่าย เวลา 12:08 ถึง… จากนั้นเวลา …". */
  fun section(section: SummarySection): String = sentence(section.part.label, section.facts.map { it.line })

  private fun sentence(lead: String, body: List<String>) = buildString {
    append(lead).append(" เวลา ").append(body.first())
    body.drop(1).forEachIndexed { i, l -> append(' ').append(connectors[i % connectors.size]).append("เวลา ").append(l) }
  }
}
