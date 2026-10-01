package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.memory.MemoryLevel

/** Human-readable Thai report (E4 "Dream report" screen data). Every line is fixed wording plus data from the run. */
internal object DreamReport {
  private const val MAX_IDS = 5

  fun render(o: DreamOutput, minDays: Int): String = buildString {
    appendLine("รายงานการย่อยความจำ คืนวันที่ ${o.day}")
    appendLine("สรุป: จัดของ ${o.tidied.size} รายการ · เลื่อนขั้น ${o.promoted.size} ข้อ · พักไว้ ${o.heldBack.size} เรื่อง")
    if (o.truncated) appendLine("หมายเหตุ: คืนนี้อ่านไม่ครบทุกวัน เพราะถึงขีดจำกัดงานต่อคืน ส่วนที่เหลือจะทำคืนถัดไป")
    appendLine()
    appendLine("จัดของ")
    if (o.tidyCancelled) appendLine("- ยกเลิกการจัดของ เพราะจะลบเกินสัดส่วนที่กำหนด ไม่มีอะไรถูกลบ")
    else if (o.tidied.isEmpty()) appendLine("- ไม่มีรายการซ้ำ")
    for (t in o.tidied) {
      val where = if (t.level == MemoryLevel.DAILY) "บันทึกวันที่ ${t.day}" else "สิ่งที่ตั้งใจจะทำ"
      val why = if (t.exact) "ข้อความซ้ำกันทุกตัวอักษร" else "ข้อความเหมือนกันเกือบทั้งหมด"
      appendLine("- ลบ “${t.text}” ($where รหัส ${t.id}) $why เก็บรหัส ${t.keptId} ไว้")
    }
    appendLine()
    appendLine("เลื่อนขั้นเป็นความจำถาวร")
    if (o.promoted.isEmpty()) appendLine("- ไม่มี")
    for (p in o.promoted) {
      appendLine("- “${p.text}” (หมวด ${p.category}) เพราะพบซ้ำใน ${p.days} วัน ผ่านเกณฑ์อย่างน้อย $minDays วัน คะแนน ${pct(p.score)} ที่มา: ${ids(p.sourceIds)}")
    }
    appendLine()
    appendLine("พักไว้ ยังไม่เลื่อนขั้น")
    if (o.heldBack.isEmpty()) appendLine("- ไม่มี")
    for (h in o.heldBack) appendLine("- ${subject(h)} ${reason(h, minDays)}${h.sourceIds.takeIf { it.isNotEmpty() }?.let { " ที่มา: ${ids(it)}" }.orEmpty()}")
  }.trimEnd() + "\n"

  /** Forgotten/rejected subjects are never printed: the user deleted them, so the text must not come back. */
  private fun subject(h: HeldBackTheme) =
    if (h.reason == HoldReason.FORGOTTEN || h.reason == HoldReason.REJECTED) "เรื่องหนึ่ง" else "“${h.subject}”"

  private fun pct(score: Double) = "${(score * 100).toInt()}%"

  private fun ids(ids: List<String>) =
    ids.take(MAX_IDS).joinToString(", ") + if (ids.size > MAX_IDS) " และอีก ${ids.size - MAX_IDS} รายการ" else ""

  private fun reason(h: HeldBackTheme, minDays: Int) = when (h.reason) {
    HoldReason.TOO_FEW_DAYS -> "พบเพียง ${h.days} วัน ต้องอย่างน้อย $minDays วัน"
    HoldReason.PROVENANCE -> "ที่มาไม่ใช่ข้อมูลของผู้ใช้ จึงเป็นความจำถาวรไม่ได้ (หลักฐานจากผู้ใช้ไม่ถึง $minDays วัน)"
    HoldReason.LOW_SCORE -> "คะแนนความถี่และความใหม่ยังต่ำกว่าเกณฑ์"
    HoldReason.FORGOTTEN -> "คุณเคยลบข้อเท็จจริงนี้แล้ว จะไม่เสนออีก"
    HoldReason.REJECTED -> "คุณเคยปฏิเสธเรื่องนี้แล้ว"
    HoldReason.ALREADY_KNOWN -> "มีอยู่ในความจำถาวรแล้ว"
    HoldReason.BUDGET -> "เต็มงบต่อคืนหรือขนาดแกนที่คัดแล้ว รอคืนถัดไป"
    HoldReason.SOURCE_MISSING -> "ต้นทางถูกลบไปก่อนเลื่อนขั้น"
  }
}
