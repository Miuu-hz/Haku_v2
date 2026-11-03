package com.bbpillow.haku.skill

/**
 * Chat mentions. `@` covers every skill (local skills that run in the phone as well as outside MCP services);
 * `/` is kept as a local-only shortcut.
 * A mention only skips the picker: the action still goes through the ActionGate card before anything leaves the phone.
 */
sealed interface Mention {
  val rest: String

  /** `/add-appointment พรุ่งนี้ 10 โมง ทำฟัน` or `/นัด …` (alias). */
  data class Skill(val skill: SkillRecord, override val rest: String) : Mention

  /**
   * Outside service mention, e.g. `@weather.example เชียงใหม่พรุ่งนี้`: the picker then chooses only among that
   * server's tools. `@` covers local skills too (yielding [Skill]); [Service] is only for MCP servers.
   */
  data class Service(val server: String, val tools: List<SkillRecord>, override val rest: String) : Mention

  /** `@nosuch …` / `/nosuch …`: a trigger plus a non-blank name that matches nothing enabled. Chat answers "ไม่พบ…". */
  data class NotFound(val trigger: Char, val name: String, override val rest: String) : Mention
}

/**
 * One row of the `@` popup (Gemini-style app list): a local skill or one outside service. Picking it fills
 * "[trigger][key] " into the input, and the sent message then runs that row's tools and nothing else.
 * [label] = what the row shows (skill title / server name); [key] = the space-free word typed after the trigger
 * (a local skill's first alias, else its name; a server's host).
 */
data class MentionOption(val key: String, val label: String, val kind: Kind, val skills: List<SkillRecord>) {
  enum class Kind { LOCAL, SERVICE }
}

object MentionParser {
  /**
   * The popup rows while the user types: [typed] = the input so far, only a trigger plus a partial word (no space).
   * `@` lists every enabled local skill (one row each) and every service (one row per server); `/` lists local only.
   * Filtered by prefix (ignore case) on key, label, aliases, name and host. [serviceNames]: host -> display name.
   * Local rows first, then services, each in input order. Empty when the popup should be hidden.
   */
  fun options(typed: String, skills: List<SkillRecord>, serviceNames: Map<String, String> = emptyMap()): List<MentionOption> {
    if (typed.isEmpty() || ' ' in typed || typed[0] !in TRIGGERS) return emptyList()
    val prefix = typed.substring(1)
    val localRows = skills.filter { it.enabled && it.server == null }.map { s ->
      MentionOption(
        key = s.aliases.firstOrNull() ?: s.name,
        label = s.title,
        kind = MentionOption.Kind.LOCAL,
        skills = listOf(s),
      )
    }
    val serviceRows = skills.filter { it.enabled && it.server != null }
      .groupBy { it.server!! }
      .map { (host, tools) ->
        MentionOption(
          key = host,
          label = serviceNames[host] ?: host,
          kind = MentionOption.Kind.SERVICE,
          skills = tools,
        )
      }
    val all = if (typed[0] == SKILL) localRows else localRows + serviceRows
    return if (prefix.isEmpty()) all else all.filter { matchesOption(it, prefix) }
  }

  /** The leading mention of a sent message, resolved against enabled [skills]; null = no mention or nothing matches. */
  fun parse(text: String, skills: List<SkillRecord>): Mention? {
    val t = text.trimStart()
    if (t.length < 2 || t[0] !in TRIGGERS) return null
    val word = t.substring(1).substringBefore(' ').trim()
    if (word.isEmpty()) return null
    val rest = t.substring(1).substringAfter(' ', "").trim()
    val enabled = skills.filter { it.enabled }
    val local = enabled.filter { it.server == null }.firstOrNull { matches(it, word, exact = true) }
    return when (t[0]) {
      SKILL -> local?.let { Mention.Skill(it, rest) }
      else -> local?.let { Mention.Skill(it, rest) }
        ?: enabled.filter { it.server.equals(word, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { Mention.Service(it.first().server!!, it, rest) }
    } ?: Mention.NotFound(t[0], word, rest)
  }

  private fun matchesOption(opt: MentionOption, prefix: String): Boolean =
    opt.key.startsWith(prefix, ignoreCase = true) ||
      opt.label.startsWith(prefix, ignoreCase = true) ||
      opt.skills.any { s ->
        s.name.startsWith(prefix, ignoreCase = true) || s.aliases.any { it.startsWith(prefix, ignoreCase = true) }
      }

  private fun matches(s: SkillRecord, word: String, exact: Boolean): Boolean {
    val keys = listOf(s.name, s.title) + s.aliases
    return if (exact) keys.any { it.equals(word, ignoreCase = true) } else keys.any { it.startsWith(word, ignoreCase = true) }
  }

  const val SKILL = '/'
  const val SERVICE = '@'
  private val TRIGGERS = charArrayOf(SKILL, SERVICE)
}
