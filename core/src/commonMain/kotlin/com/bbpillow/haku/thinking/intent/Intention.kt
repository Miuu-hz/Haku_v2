package com.bbpillow.haku.thinking.intent

import com.bbpillow.haku.memory.MemoryFormat

/** What kind of new thing may set an intention off. ANY = place, appointment or note. */
enum class IntentScope(val key: String) { ANY("any"), PLACE("place"), APPOINTMENT("appointment"), NOTE("note") }

/** A parsed "ถ้า X ให้เตือน Y", before it is stored. [trigger] is the X words, all required (P5.3). */
data class IntentionInput(
  val trigger: String,
  val reminder: String,
  val scope: IntentScope,
  val maxFires: Int = 1,
  val cooldownMs: Long = Intention.DEFAULT_COOLDOWN_MS,
  val expiresInMs: Long = Intention.DEFAULT_TTL_MS,
)

/**
 * One standing intention as stored in INTENTIONS.md. [id] is the memory record id. [placeId] pins a place trigger
 * to one confirmed place (rename-proof); without it the [trigger] words are matched against the place name.
 */
data class Intention(
  val id: String,
  val trigger: String,
  val reminder: String,
  val scope: IntentScope,
  val maxFires: Int,
  val cooldownMs: Long,
  val expiresAtMs: Long,
  val createdAtMs: Long,
  val fired: Int = 0,
  val lastFiredAtMs: Long? = null,
  val placeId: String? = null,
) {
  fun isLive(nowMs: Long): Boolean = fired < maxFires && nowMs < expiresAtMs

  companion object {
    const val DEFAULT_COOLDOWN_MS = 6 * 3_600_000L
    const val DEFAULT_TTL_MS = 30 * 86_400_000L
  }
}

/** Something new that happened: an arrival at a confirmed place, a calendar row, or a note the user typed. */
data class IntentTriggerEvent(val scope: IntentScope, val text: String, val atMs: Long, val placeId: String? = null)

/** One reminder to show now, worded in core: [title] carries the reminder, [text] the condition that was met. */
data class IntentFireOutput(val intentionId: String, val title: String, val text: String)

/**
 * The record text inside INTENTIONS.md: two Thai lines a person can read plus one rule line, e.g.
 * ```
 * เมื่อ: ที่ทำงาน
 * เตือน: ส่งรายงาน
 * กติกา: scope=place max=1 fired=0 cooldown_min=360 expires=2026-11-28T00:00:00.000Z last=- place=-
 * ```
 * Records without this shape (older free-text intentions) decode to null: listed, never fired.
 */
object IntentionText {
  private const val WHEN = "เมื่อ: "
  private const val REMIND = "เตือน: "
  private const val RULES = "กติกา: "

  fun encode(i: Intention): String = buildString {
    append(WHEN).append(oneLine(i.trigger)).append('\n')
    append(REMIND).append(oneLine(i.reminder)).append('\n')
    append(RULES).append("scope=").append(i.scope.key).append(" max=").append(i.maxFires).append(" fired=").append(i.fired)
      .append(" cooldown_min=").append(i.cooldownMs / 60_000).append(" expires=").append(MemoryFormat.formatUtc(i.expiresAtMs))
      .append(" last=").append(i.lastFiredAtMs?.let(MemoryFormat::formatUtc) ?: "-").append(" place=").append(i.placeId ?: "-")
  }

  fun decode(id: String, text: String, createdAtMs: Long): Intention? {
    val lines = text.lines()
    val trigger = lines.firstOrNull { it.startsWith(WHEN) }?.removePrefix(WHEN)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val reminder = lines.firstOrNull { it.startsWith(REMIND) }?.removePrefix(REMIND)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val kv = lines.firstOrNull { it.startsWith(RULES) }?.removePrefix(RULES)?.split(' ')
      ?.mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }?.toMap() ?: return null
    return Intention(
      id = id,
      trigger = trigger,
      reminder = reminder,
      scope = IntentScope.entries.firstOrNull { it.key == kv["scope"] } ?: return null,
      maxFires = kv["max"]?.toIntOrNull() ?: return null,
      cooldownMs = (kv["cooldown_min"]?.toLongOrNull() ?: return null) * 60_000,
      expiresAtMs = kv["expires"]?.let(MemoryFormat::parseUtc) ?: return null,
      createdAtMs = createdAtMs,
      fired = kv["fired"]?.toIntOrNull() ?: 0,
      lastFiredAtMs = kv["last"]?.let(MemoryFormat::parseUtc),
      placeId = kv["place"]?.takeIf { it != "-" && it.isNotEmpty() },
    )
  }

  /** "เมื่อถึงที่ทำงาน" / "เมื่อมีนัด “หมอ”": the condition as the chat reply and the list say it. */
  fun whenText(i: Intention): String = when (i.scope) {
    IntentScope.PLACE -> "เมื่อถึง${i.trigger}"
    IntentScope.APPOINTMENT -> "เมื่อมีนัด “${i.trigger}”"
    IntentScope.NOTE -> "เมื่อคุณจดเรื่อง “${i.trigger}”"
    IntentScope.ANY -> "เมื่อเจอเรื่อง “${i.trigger}”"
  }

  private fun oneLine(s: String) = s.replace('\n', ' ').trim()
}
