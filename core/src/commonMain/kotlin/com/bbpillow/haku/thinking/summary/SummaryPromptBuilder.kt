package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.language.TokenEstimator

/** Compact Thai prompt: rewrite the given facts, add nothing (D14). Facts are cut to fit [budgetTokens]. */
object SummaryPromptBuilder {
  const val DEFAULT_BUDGET_TOKENS = 900

  private const val FACTS = "ข้อเท็จจริง:\n"
  private const val CONTEXT = "ข้อมูลประกอบ (ใช้กำหนดน้ำเสียงเท่านั้น ห้ามนำมาเป็นข้อเท็จจริงของวันนี้):\n"
  private const val TAIL = "\nสรุป:"

  fun build(input: SummaryPromptInput, budgetTokens: Int = DEFAULT_BUDGET_TOKENS): SummaryPromptOutput {
    val head = head(input)
    var used = TokenEstimator.estimate(head + FACTS + CONTEXT + TAIL)
    fun fits(line: String): Boolean {
      val cost = TokenEstimator.estimate("- $line\n")
      return (used + cost <= budgetTokens).also { if (it) used += cost }
    }
    // ponytail: keeps the earliest lines that fit; the diary is capped at 12 lines so this rarely bites.
    val facts = input.lines.takeWhile(::fits)
    val context = input.contextLines.takeWhile(::fits)
    val prompt = buildString {
      append(head).append(FACTS)
      facts.forEach { append("- ").append(it).append('\n') }
      if (context.isNotEmpty()) {
        append(CONTEXT)
        context.forEach { append("- ").append(it).append('\n') }
      }
      append(TAIL)
    }
    return SummaryPromptOutput(prompt, facts, TokenEstimator.estimate(prompt))
  }

  private fun head(input: SummaryPromptInput) = buildString {
    append("เขียนสรุปของ").append(input.header).append(" ให้เป็นย่อหน้าเดียวสั้น ๆ 2 ถึง 4 ประโยค อ่านลื่นไหลเหมือนเล่าให้เพื่อนฟัง\n")
    append("กติกา:\n")
    append("- ใช้เฉพาะข้อเท็จจริงในรายการด้านล่าง ห้ามเพิ่มข้อมูลใหม่ ห้ามเดา\n")
    append("- ใช้เวลา ตัวเลข และชื่อสถานที่ตามที่ให้ไว้เท่านั้น ห้ามคิดเวลาหรือชื่อสถานที่ขึ้นเอง\n")
    append("- เรียงตามลำดับเวลา ห้ามใส่ความรู้สึกหรือเหตุผลที่ไม่มีในรายการ\n")
    append("- เขียนเป็นภาษาไทยล้วน ไม่มีหัวข้อ ไม่มีรายการข้อ\n")
    append(particleRule(input.style.particle)).append('\n')
  }

  private fun particleRule(p: EndingParticle) = when (p) {
    EndingParticle.NONE -> "- ไม่ใช้คำลงท้าย ครับ ค่ะ คะ"
    else -> "- ประโยคสุดท้ายลงท้ายด้วย “${p.text}” และไม่ใช้คำลงท้ายอื่น"
  }
}
