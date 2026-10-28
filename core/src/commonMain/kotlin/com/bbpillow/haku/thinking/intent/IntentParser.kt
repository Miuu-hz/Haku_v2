package com.bbpillow.haku.thinking.intent

import com.bbpillow.haku.language.Normalizer
import com.bbpillow.haku.language.TextInput

/**
 * P5.3 rule-based reading of "ถ้า/เมื่อ X ให้เตือน/บอก Y" (no model). Returns null for anything else, including
 * questions ("ถ้าฉันบอกว่าเหนื่อยต้องทำยังไง"), so chat keeps working for them.
 * ponytail: forward order only ("ถ้า X … เตือน Y"); "เตือน Y ตอน X" reads like a time reminder, which is not this.
 */
object IntentParser {
  private val shape = Regex(
    "^(?:ถ้าหาก|ถ้า|หาก|เมื่อไหร่ที่|เมื่อไรที่|เมื่อไหร่|เมื่อไร|เมื่อ|ตอนที่|พอ)\\s*(.+?)\\s*(?:แล้ว\\s*)?(?:ก็\\s*)?" +
      "(?:(?:ช่วย\\s*)?(?:ให้\\s*)?เตือน|(?:ให้|ช่วย)\\s*บอก|บอกให้)" +
      "\\s*(?:ฉัน|ผม|หนู|เรา|ด้วย)?\\s*(?:ด้วย)?\\s*(?:ให้|ว่า)?\\s*(.+)$",
  )
  private val question = Regex("(ไหม|มั้ย|หรือเปล่า|หรือยัง|อะไร|ยังไง|อย่างไร|ทำไม|\\?)\\s*$")

  // Leading words that say what kind of event sets it off; stripped from the trigger. Longest first.
  private val place = listOf("กลับถึง", "ไปถึง", "มาถึง", "ถึง", "อยู่ที่", "ไป")
  private val appointment = listOf("มีนัดกับ", "มีนัด", "นัดกับ", "นัด")
  private val meeting = listOf("มีประชุม") // the word stays: calendar titles say "ประชุม…"
  private val note = listOf("จดเรื่อง", "จดว่า", "จด", "บันทึกเรื่อง", "บันทึกว่า", "เขียนเรื่อง")
  private val any = listOf("เจอเรื่อง", "เจอคำว่า", "พูดถึงเรื่อง", "พูดถึง", "มีเรื่อง", "เจอ", "เรื่อง", "คำว่า")

  private val everyTime = Regex("\\s*ทุกครั้ง(?:ที่เจอ)?")
  private val everyDay = Regex("\\s*ทุกวัน")
  private val once = Regex("\\s*(?:แค่)?ครั้งเดียว")
  private val times = Regex("\\s*(\\d{1,2})\\s*ครั้ง")
  private val withinDays = Regex("\\s*ภายใน\\s*(\\d{1,3})\\s*วัน")
  private val thisWeek = Regex("\\s*(?:ภายใน)?สัปดาห์นี้")
  private const val HOUR = 3_600_000L
  private const val DAY = 86_400_000L

  fun parse(text: String): IntentionInput? {
    var s = Normalizer.normalize(TextInput(text.trim(), "th")).normalized.replace(Regex("\\s+"), " ")
    if (question.containsMatchIn(s)) return null
    var maxFires = 1
    var cooldown = Intention.DEFAULT_COOLDOWN_MS
    var ttl = Intention.DEFAULT_TTL_MS
    fun take(r: Regex): MatchResult? = r.find(s)?.also { s = s.removeRange(it.range) }
    take(withinDays)?.let { ttl = it.groupValues[1].toLong().coerceIn(1, 365) * DAY }
    take(thisWeek)?.let { ttl = 7 * DAY }
    when {
      take(everyTime) != null -> { maxFires = 20; cooldown = 12 * HOUR }
      take(everyDay) != null -> { maxFires = 30; cooldown = 20 * HOUR }
      take(once) != null -> maxFires = 1
      else -> take(times)?.let { maxFires = it.groupValues[1].toInt().coerceIn(1, 20) }
    }
    val m = shape.matchEntire(s.trim()) ?: return null
    val (scope, trigger) = scopeOf(m.groupValues[1].trim())
    val reminder = m.groupValues[2].trim()
    if (trigger.isEmpty() || reminder.isEmpty()) return null
    return IntentionInput(trigger, reminder, scope, maxFires, cooldown, ttl)
  }

  private fun scopeOf(cond: String): Pair<IntentScope, String> {
    fun strip(prefixes: List<String>) = prefixes.firstOrNull { cond.startsWith(it) }?.let { cond.removePrefix(it).trim() }
    strip(place)?.let { return IntentScope.PLACE to it }
    strip(appointment)?.let { return IntentScope.APPOINTMENT to it }
    if (meeting.any { cond.startsWith(it) }) return IntentScope.APPOINTMENT to cond.removePrefix("มี")
    strip(note)?.let { return IntentScope.NOTE to it.removePrefix("เรื่อง").trim() }
    strip(any)?.let { return IntentScope.ANY to it.removePrefix("เรื่อง").removePrefix("คำว่า").trim() }
    return IntentScope.ANY to cond
  }
}
