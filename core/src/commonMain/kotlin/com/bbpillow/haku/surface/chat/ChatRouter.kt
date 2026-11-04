package com.bbpillow.haku.surface.chat

/** DIARY = the existing path, checked against the user's records. GENERAL = the model's own knowledge, labelled as possibly wrong. */
enum class ChatRoute { DIARY, GENERAL }

object ChatRouter {
  private val STRONG_LIFE = listOf("ฉัน", "ตัวเอง", "ล่าสุด", "ครั้งก่อน", "ไปไหน", "ไปที่ไหน", "นัด", "บันทึก", "จำได้")

  // Evaluated after KNOWLEDGE because pronouns appear inside ordinary words such as ผมร่วง and เราเตอร์.
  private val WEAK_PRONOUN = listOf("ผม", "เรา", "หนู")

  private val KNOWLEDGE = listOf(
    "สูตร", "วิธี", "ทำยังไง", "ทำอย่างไร", "แก้ยังไง", "ใช้ยังไง", "ควรทำ", "ต่างกัน",
    "ข้อดี", "ข้อเสีย", "คืออะไร", "หมายถึง", "แปล", "อธิบาย", "คำนวณ", "เท่ากับ",
    "ทำไม", "เล่า", "แต่ง", "เขียน", "แนะนำ"
  )

  private val ARITHMETIC_REGEX = Regex("""\d\s*[-+*/x×]\s*\d""")

  /**
   * @param isQuestion the message asks something (ChatPresenter.isQuestion)
   * @param hasTimeWord the message names a date or period the diary can look up (TimeWords.find found one)
   * @param hasWordHit the word search found a record or core fact for this message
   */
  fun route(question: String, isQuestion: Boolean, hasTimeWord: Boolean, hasWordHit: Boolean): ChatRoute {
    val trimmed = question.trim()

    // 1. hasTimeWord or any STRONG_LIFE cue -> DIARY
    if (hasTimeWord || STRONG_LIFE.any { trimmed.contains(it) }) {
      return ChatRoute.DIARY
    }

    // 2. any KNOWLEDGE cue, or an arithmetic expression -> GENERAL
    if (KNOWLEDGE.any { trimmed.contains(it) } || ARITHMETIC_REGEX.containsMatchIn(trimmed)) {
      return ChatRoute.GENERAL
    }

    // 3. any WEAK_PRONOUN cue -> DIARY
    if (WEAK_PRONOUN.any { trimmed.contains(it) }) {
      return ChatRoute.DIARY
    }

    // 4. hasWordHit -> DIARY
    if (hasWordHit) {
      return ChatRoute.DIARY
    }

    // 5. isQuestion -> GENERAL
    if (isQuestion) {
      return ChatRoute.GENERAL
    }

    // 6. otherwise -> DIARY (a statement keeps today's short acknowledgement path)
    return ChatRoute.DIARY
  }
}
