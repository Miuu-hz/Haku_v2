package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.language.ThaiDateFormat

/** The deterministic fallback paragraph, built only from diary lines. Never blank, never invented. */
object SummaryTemplate {
  const val NO_DATA = "วันนี้ยังไม่มีบันทึกอะไรเลย ไว้พรุ่งนี้มาดูกันใหม่"
  private val connectors = listOf("จากนั้น", "ต่อมา", "หลังจากนั้น")

  fun noData(style: DiaryStyle) = NO_DATA + style.particle.text

  /** "วันจันทร์ที่ 7 ... เวลา 08:30 ออกจากบ้าน จากนั้นเวลา 09:10 ถึงที่ทำงาน ..." with the style's particle at the end. */
  fun paragraph(day: String, lines: List<String>, style: DiaryStyle): String {
    if (lines.isEmpty()) return noData(style)
    val body = lines.map { it.trim().removeSuffix(style.particle.text) }.filter { it.isNotEmpty() }
    if (body.isEmpty()) return noData(style)
    return buildString {
      append(ThaiDateFormat.dayHeader(day)).append(" เวลา ").append(body.first())
      body.drop(1).forEachIndexed { i, l -> append(' ').append(connectors[i % connectors.size]).append("เวลา ").append(l) }
      append(style.particle.text)
    }
  }
}
