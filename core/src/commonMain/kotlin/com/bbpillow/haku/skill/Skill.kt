package com.bbpillow.haku.skill

/*
 * Week 2 shared contract: skills for a 2–4B on-device model, MCP tools shown as skills, and chat mentions.
 * One skill = one step: a one-line description the picker embeds, short instructions, at most three slots the model
 * fills through a tool call, and an [SkillRecord.action] the ActionGate runs (so every skill gets the card, the
 * audit line and the emergency stop). Owned by Claude: agents implement against it but do not change it.
 */

/** Where a skill came from. MCP tools are listed as skills too, so the picker and the mention list see one list. */
enum class SkillSource { BUILT_IN, IMPORTED, MCP }

/** A value the model fills. [required] slots must be present and non-blank before the action is proposed. */
data class SkillSlot(val name: String, val description: String, val required: Boolean = true)

/**
 * @property name slug, also the mention: `/add-appointment`. Lowercase ASCII, digits and '-'.
 * @property title Thai label in the mention list and on the card, e.g. "เพิ่มนัด".
 * @property description one line, what the picker embeds and the model reads when choosing.
 * @property instructions what the model reads after the skill is chosen; at most [MAX_INSTRUCTION_CHARS].
 * @property slots at most [MAX_SLOTS].
 * @property action ActionGate action id run with the validated slots, e.g. `calendar.add`, or `mcp.call` for MCP.
 * @property aliases Thai words that also match a mention: "/นัด" finds add-appointment.
 * @property server MCP only: the service host the mention `@server` names; null for local skills.
 * @property readOnly MCP only: the server marks this tool as changing nothing (`readOnlyHint`), so a kept result may answer it.
 */
data class SkillRecord(
  val name: String,
  val title: String,
  val description: String,
  val instructions: String,
  val slots: List<SkillSlot>,
  val action: String,
  val aliases: List<String> = emptyList(),
  val source: SkillSource = SkillSource.BUILT_IN,
  val enabled: Boolean = true,
  val server: String? = null,
  val readOnly: Boolean = false,
) {
  init {
    require(NAME_RULE.matches(name)) { "bad skill name '$name'" }
    require(slots.size <= MAX_SLOTS) { "$name: ${slots.size} slots, max $MAX_SLOTS" }
    require(instructions.length <= MAX_INSTRUCTION_CHARS) { "$name: instructions ${instructions.length} chars, max $MAX_INSTRUCTION_CHARS" }
    require(slots.map { it.name }.toSet().size == slots.size) { "$name: duplicate slot" }
  }

  /**
   * Keeps only declared slots, trims them, caps each at [MAX_SLOT_CHARS]. Fails on a missing required slot.
   * The model's tool call is untrusted input: unknown keys are dropped, never passed to the action.
   */
  fun validate(arguments: Map<String, String>): SlotResult {
    val kept = slots.mapNotNull { slot -> arguments[slot.name]?.trim()?.takeIf { it.isNotEmpty() }?.let { slot.name to it.take(MAX_SLOT_CHARS) } }.toMap()
    val missing = slots.filter { it.required && it.name !in kept }.map { it.name }
    return if (missing.isEmpty()) SlotResult.Ok(kept) else SlotResult.Missing(missing)
  }

  companion object {
    const val MAX_SLOTS = 3
    /** ~150 Gemma tokens of Thai/English. ponytail: a char cap, not a tokenizer; tighten if the eval shows drift. */
    const val MAX_INSTRUCTION_CHARS = 600
    const val MAX_SLOT_CHARS = 300
    val NAME_RULE = Regex("[a-z0-9][a-z0-9-]{0,39}")
  }
}

sealed interface SlotResult {
  data class Ok(val arguments: Map<String, String>) : SlotResult
  /** The model should ask the user for these, in Thai, instead of guessing. */
  data class Missing(val slots: List<String>) : SlotResult
}

/** Installed skills (built-in + imported + MCP tools). Enabled flags persist through the sealed file system. */
interface SkillRepository {
  fun all(): List<SkillRecord>
  fun setEnabled(name: String, enabled: Boolean)
}

/** Chooses the skill for a message nobody mentioned. Implementation: EmbeddingGemma cosine over [SkillRecord.description]. */
interface SkillPicker {
  /** Best enabled skill above the confidence floor, or null = plain chat. */
  suspend fun pick(text: String, skills: List<SkillRecord>): SkillRecord?
}
