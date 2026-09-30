package com.bbpillow.haku.memory

/**
 * P3.4 export: every memory record as one readable Markdown document (standing, intentions, core by category, daily
 * log, dream reports). Record text is written verbatim, one block per record, so nothing is reworded or cut.
 * User-initiated and handed to the OS file picker by the caller: it is not network egress.
 */
object MemoryExport {
  fun markdown(memory: MemoryRepository): String = buildString {
    appendLine("# Haku: ความจำทั้งหมดของคุณ")
    section("คำสั่งถาวร", memory.readStanding())
    section("สิ่งที่ต้องจำไว้ทำ", memory.readIntentions())
    for (c in memory.listCategories()) section("หมวด ${c.slug}", memory.readCategory(c.slug))
    for (day in memory.listDays()) section("บันทึกประจำวัน $day", memory.readDay(day))
    for (day in memory.listDreamDays()) memory.readDreamReport(day)?.let { appendLine("\n## รายงานการย่อยความจำ $day\n\n$it") }
  }

  private fun StringBuilder.section(title: String, records: List<MemoryRecord>) {
    if (records.isEmpty()) return
    appendLine("\n## $title")
    for (r in records) appendLine("\n### ${MemoryFormat.formatUtc(r.atEpochMs)} (${r.provenance.key})\n\n${r.text}")
  }
}
