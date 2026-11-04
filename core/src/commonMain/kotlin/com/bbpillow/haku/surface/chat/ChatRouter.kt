package com.bbpillow.haku.surface.chat

/** DIARY = the existing path, checked against the user's records. GENERAL = the model's own knowledge, labelled as possibly wrong. */
enum class ChatRoute { DIARY, GENERAL }

object ChatRouter {
  private val STRONG_LIFE = listOf("ฉัน", "ตัวเอง", "ล่าสุด", "ครั้งก่อน", "ไปไหน", "ไปที่ไหน", "นัด", "บันทึก", "จำได้", "วันไหน", "เดือนไหน", "ที่แล้ว")

  // Thai drops the subject: "ไปเซ็นทรัลเวิลด์ทำไม" is about the user even with a knowledge cue. Not ไปรษณีย์.
  private val STARTS_WITH_GOING = Regex("""^ไป(?!ร)""")

  // Evaluated after KNOWLEDGE because pronouns appear inside ordinary words such as ผมร่วง and เราเตอร์.
  private val WEAK_PRONOUN = listOf("ผม", "เรา", "หนู")

  private val KNOWLEDGE = listOf(
    "สูตร", "วิธี", "ทำยังไง", "ทำอย่างไร", "แก้ยังไง", "ใช้ยังไง", "ควรทำ", "ต่างกัน",
    "ข้อดี", "ข้อเสีย", "คืออะไร", "หมายถึง", "แปล", "อธิบาย", "คำนวณ", "เท่ากับ",
    "ทำไม", "เล่า", "แต่ง", "เขียน", "แนะนำ"
  )

  private val ARITHMETIC_REGEX = Regex("""\d\s*[-+*/x×]\s*\d""")

  private val FOLLOW_UP = listOf(
    "อันนั้น", "อันนี้", "เรื่องนั้น", "เรื่องนี้", "ข้อนั้น", "ข้อนี้", "ที่ว่า", "ที่บอก",
    "ต่อสิ", "ต่อเลย", "ต่ออีก", "ขยายความ", "ยกตัวอย่าง", "อีกที", "กว่านี้",
  )
  private val ENDS_LIKE_FOLLOW_UP = Regex("""(ล่ะ|หละ)[\s?？]*$""")

  /** The message leans on the turn before it ("แล้วอันนั้นล่ะ"), so on its own it says little about where it belongs. */
  fun isFollowUp(question: String): Boolean {
    val trimmed = question.trim()
    return trimmed.startsWith("แล้ว") || ENDS_LIKE_FOLLOW_UP.containsMatchIn(trimmed) || FOLLOW_UP.any { it in trimmed }
  }

  /**
   * @param isQuestion the message asks something (ChatPresenter.isQuestion)
   * @param hasTimeWord the message names a date or period the diary can look up, or asks about right now
   * @param hasWordHit the word search found a record or core fact for this message
   * @param previous the route of the turn before, null at the start of a chat
   */
  fun route(question: String, isQuestion: Boolean, hasTimeWord: Boolean, hasWordHit: Boolean, previous: ChatRoute? = null): ChatRoute {
    val trimmed = question.trim()

    // 1. hasTimeWord or any STRONG_LIFE cue -> DIARY
    if (hasTimeWord || STRONG_LIFE.any { trimmed.contains(it) } || STARTS_WITH_GOING.containsMatchIn(trimmed)) {
      return ChatRoute.DIARY
    }

    // 2. any KNOWLEDGE cue, or an arithmetic expression -> GENERAL
    if (KNOWLEDGE.any { trimmed.contains(it) } || ARITHMETIC_REGEX.containsMatchIn(trimmed)) {
      return ChatRoute.GENERAL
    }

    // ponytail: "แล้วทำไมถึงไป" after a diary answer has a knowledge cue and goes general; a follow-up classifier if testers hit it
    // a follow-up stays where the chat already is
    if (previous != null && isFollowUp(trimmed)) {
      return previous
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
