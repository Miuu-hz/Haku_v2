package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.thinking.intent.IntentParser
import com.bbpillow.haku.thinking.intent.IntentService
import com.bbpillow.haku.thinking.intent.IntentionText

/**
 * P5.3 pre-check for the chat box: "ถ้าไปถึงที่ทำงานให้เตือนส่งรายงาน" is stored as an intention and answered with a
 * confirmation, no model and no retrieval. Callers try this before [ChatPresenter.retrieve]; null = an ordinary question.
 * Runs on the DB thread (it writes INTENTIONS.md).
 */
class IntentCommand(private val intents: IntentService) {
  fun tryHandle(question: String, nowMs: Long): ChatAnswerOutput? {
    val input = IntentParser.parse(question) ?: return null
    val saved = intents.add(input, nowMs)
    val text = if (saved == null) UNCLEAR else {
      val times = if (saved.maxFires == 1) "ครั้งเดียว" else "ได้ถึง ${saved.maxFires} ครั้ง"
      "จะเตือน “${saved.reminder}” ${IntentionText.whenText(saved)} ($times ภายใน ${input.expiresInMs / DAY_MS} วัน)\n" +
        "ดูหรือยกเลิกได้ที่ สิ่งที่ Haku จำ › ความตั้งใจค้างไว้"
    }
    return ChatAnswerOutput(question, text, emptyList(), ChatAnswerSource.RETRIEVAL)
  }

  companion object {
    private const val DAY_MS = 86_400_000L
    const val UNCLEAR = "ยังตั้งเตือนไม่ได้ ช่วยบอกสิ่งที่ต้องเจอให้ชัดขึ้น เช่น “ถ้าถึงที่ทำงานให้เตือนส่งรายงาน”"
  }
}
