package com.bbpillow.haku.skill

/**
 * SKILL.md, compatible with Gallery / agentskills (`name`, `description` frontmatter + Markdown body) plus Haku keys:
 * ```
 * ---
 * name: add-appointment
 * description: Add one appointment to the phone calendar
 * title: เพิ่มนัด
 * aliases: นัด, ปฏิทิน
 * action: calendar.add
 * slots: title=ชื่อนัด; when=วันและเวลา; place?=สถานที่
 * ---
 * <instructions>
 * ```
 * `?` after a slot name = optional. A Gallery skill without `action`/`slots` is rejected: multi-step Gallery skills
 * are too long for a 2–4B model and go to the PC (P7.0) instead.
 */
object SkillFormat {
  fun parse(md: String, source: SkillSource = SkillSource.IMPORTED): SkillRecord {
    val text = md.replace("\r\n", "\n").trimStart('﻿')
    require(text.startsWith("---\n")) { "SKILL.md must start with --- frontmatter" }
    val end = text.indexOf("\n---", 4)
    require(end > 0) { "SKILL.md frontmatter is not closed" }
    val meta = text.substring(4, end).lines().filter { ':' in it }
      .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    fun key(k: String) = requireNotNull(meta[k]?.takeIf { it.isNotBlank() }) { "SKILL.md needs '$k'" }
    return SkillRecord(
      name = key("name"),
      title = meta["title"]?.takeIf { it.isNotBlank() } ?: key("name"),
      description = key("description"),
      instructions = text.substring(end + 4).trim(),
      slots = parseSlots(key("slots")),
      action = key("action"),
      aliases = meta["aliases"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
      source = source,
    )
  }

  fun write(s: SkillRecord): String = buildString {
    appendLine("---")
    appendLine("name: ${s.name}")
    appendLine("description: ${s.description}")
    appendLine("title: ${s.title}")
    if (s.aliases.isNotEmpty()) appendLine("aliases: ${s.aliases.joinToString(", ")}")
    appendLine("action: ${s.action}")
    appendLine("slots: " + s.slots.joinToString("; ") { "${it.name}${if (it.required) "" else "?"}=${it.description}" })
    appendLine("---")
    append(s.instructions)
  }

  private fun parseSlots(raw: String): List<SkillSlot> = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
    val name = part.substringBefore('=').trim()
    SkillSlot(name.removeSuffix("?"), part.substringAfter('=', "").trim(), required = !name.endsWith("?"))
  }
}
