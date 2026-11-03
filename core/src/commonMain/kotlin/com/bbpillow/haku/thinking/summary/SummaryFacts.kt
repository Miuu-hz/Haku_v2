package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.EndingParticle

/**
 * One diary line as `เวลา · สถานที่ · ทำอะไร` ("09:40 · ที่ทำงาน · ถึง อยู่ราว 3 ชั่วโมง"). [place] is empty for an
 * appointment or an unknown shape. [line] = the diary line without its particle: the checker's facts for this fact.
 */
data class SummaryFact(val time: String, val place: String, val what: String, val line: String) {
  val structured get() = listOf(time, place, what).filter { it.isNotEmpty() }.joinToString(" · ")

  /** Minutes after local midnight; -1 when the line has no time. */
  val minutes: Int get() = time.takeIf { it.isNotEmpty() }?.split(':')?.let { (h, m) -> h.toInt() * 60 + m.toInt() } ?: -1
}

/** Local time parts of a day; [label] opens that part's sentence. */
enum class SummaryPart(val label: String, val fromMinutes: Int) {
  MORNING("ช่วงเช้า", 0),
  AFTERNOON("ช่วงบ่าย", 12 * 60),
  EVENING("ช่วงเย็น", 18 * 60),
}

data class SummarySection(val part: SummaryPart, val facts: List<SummaryFact>)

/** Pure: the day's diary lines (DiaryTemplates shapes, calendar items included) to time-sorted facts, then parts. */
object SummaryFacts {
  private val timed = Regex("""^(\d{1,2})[:.](\d{2})\s+(.*)$""")
  private val arrival = Regex("""^ถึง(.+?)((?: ใช้เวลา| อยู่).*)?$""")

  fun extract(lines: List<String>, particle: EndingParticle): List<SummaryFact> = lines.mapNotNull { raw ->
    val line = particle.stripFrom(raw.trim()).trimEnd()
    if (line.isEmpty()) return@mapNotNull null
    val m = timed.find(line)
    val time = m?.let { "${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}" }.orEmpty()
    val body = m?.groupValues?.get(3) ?: line
    val arrive = arrival.find(body)
    when {
      body.startsWith("ออกจาก") -> SummaryFact(time, body.removePrefix("ออกจาก").trim(), "ออกจาก", line)
      arrive != null -> SummaryFact(time, arrive.groupValues[1].trim(), "ถึง" + arrive.groupValues[2], line)
      else -> SummaryFact(time, "", body, line) // "มีนัด “…” ถึง 16:00" and anything newer
    }
  }.sortedBy { it.minutes }

  // ponytail: a line without a time (never written by DiaryRenderer) sorts first and reads as morning.
  fun sections(facts: List<SummaryFact>): List<SummarySection> {
    val byPart = facts.groupBy { f -> SummaryPart.entries.lastOrNull { f.minutes >= it.fromMinutes } ?: SummaryPart.MORNING }
    return SummaryPart.entries.mapNotNull { p -> byPart[p]?.let { SummarySection(p, it) } }
  }
}
