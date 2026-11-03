package com.bbpillow.haku.skill

/**
 * Chat mentions. `/` names a skill that runs in the phone, `@` names an outside service (MCP server, later the PC).
 * A mention only skips the picker: the action still goes through the ActionGate card before anything leaves the phone.
 */
sealed interface Mention {
  val rest: String

  /** `/add-appointment พรุ่งนี้ 10 โมง ทำฟัน` or `/นัด …` (alias). */
  data class Skill(val skill: SkillRecord, override val rest: String) : Mention

  /** `@weather.example เชียงใหม่พรุ่งนี้`: the picker then chooses only among that server's tools. */
  data class Service(val server: String, val tools: List<SkillRecord>, override val rest: String) : Mention

  /** `@nosuch …` / `/nosuch …`: a trigger plus a non-blank name that matches nothing enabled. Chat answers "ไม่พบ…". */
  data class NotFound(val trigger: Char, val name: String, override val rest: String) : Mention
}

object MentionParser {
  /** The leading mention of a sent message, resolved against enabled [skills]; null = no mention or nothing matches. */
  fun parse(text: String, skills: List<SkillRecord>): Mention? {
    val t = text.trimStart()
    if (t.length < 2 || t[0] !in TRIGGERS) return null
    val word = t.substring(1).substringBefore(' ').trim()
    if (word.isEmpty()) return null
    val rest = t.substring(1).substringAfter(' ', "").trim()
    val enabled = skills.filter { it.enabled }
    return when (t[0]) {
      SKILL -> enabled.filter { it.server == null }.firstOrNull { matches(it, word, exact = true) }?.let { Mention.Skill(it, rest) }
      else -> enabled.filter { it.server.equals(word, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { Mention.Service(it.first().server!!, it, rest) }
    } ?: Mention.NotFound(t[0], word, rest)
  }

  /**
   * What the popup lists while the user types: [typed] is the text so far. Only when the text is just a trigger
   * plus a partial word (no space yet). `/` lists local skills, `@` lists services (one entry per server).
   */
  fun suggest(typed: String, skills: List<SkillRecord>): List<SkillRecord> {
    val t = typed.trimStart()
    if (t.isEmpty() || t[0] !in TRIGGERS || ' ' in t) return emptyList()
    val word = t.substring(1)
    val enabled = skills.filter { it.enabled }
    return if (t[0] == SKILL) {
      enabled.filter { it.server == null && matches(it, word, exact = false) }
    } else {
      enabled.filter { it.server != null && it.server.startsWith(word, ignoreCase = true) }.distinctBy { it.server }
    }
  }

  private fun matches(s: SkillRecord, word: String, exact: Boolean): Boolean {
    val keys = listOf(s.name, s.title) + s.aliases
    return if (exact) keys.any { it.equals(word, ignoreCase = true) } else keys.any { it.startsWith(word, ignoreCase = true) }
  }

  const val SKILL = '/'
  const val SERVICE = '@'
  private val TRIGGERS = charArrayOf(SKILL, SERVICE)
}
