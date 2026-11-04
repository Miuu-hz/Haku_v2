package com.bbpillow.haku.skill

import com.bbpillow.haku.action.ActionRequestInput

/** One result the user already confirmed and fetched from a tool, kept so the same question needs no second call. */
data class ToolResultRecord(
  val key: String,
  val service: String,
  val tool: String,
  val arguments: Map<String, String>,
  val text: String,
  val fetchedUtcMs: Long,
  val freshUntilUtcMs: Long,
)

/** Where an answer's tool result came from, for the chip under the answer. [key] finds the call again for "ดึงใหม่". */
data class ToolSource(val key: String, val service: String, val fetchedUtcMs: Long, val freshUntilUtcMs: Long, val fromMemory: Boolean)

/** Sensitive: real business data lives here. Same encrypted database as the diary; forget-everything calls [clear]. */
interface ToolMemoryRepository {
  fun find(key: String): ToolResultRecord?

  /** Replaces any record with the same key. */
  fun put(record: ToolResultRecord)

  /** Returns how many records were removed. */
  fun deleteFetchedBefore(cutoffUtcMs: Long): Int

  /** The user removed this service: what it returned goes with it. */
  fun deleteService(service: String)

  fun clear()
}

object ToolMemory {
  const val KEEP_RAW_MS = 30L * 24 * 60 * 60 * 1000

  /** Local hour every result stops being fresh, so the morning starts from new data whether or not the phone charged. */
  const val RESET_HOUR = 4

  private const val MIN_MS = 60_000L
  private const val HOUR_MS = 3_600_000L
  private const val DAY_MS = 86_400_000L

  /**
   * Exact match only: an answer about shop A must never serve shop B, so values are compared as written apart from
   * surrounding spaces. A meaning-based match waits until it is measured and guarded.
   */
  fun keyOf(service: String, tool: String, arguments: Map<String, String>): String =
    (listOf(service.lowercase(), tool) + arguments.entries.sortedBy { it.key }.map { "${it.key}=${it.value.trim()}" })
      .joinToString(SEPARATOR)

  /** The key of an `mcp.call` request, null for any other action. */
  fun keyOf(request: ActionRequestInput): String? =
    parts(request)?.let { (service, tool, arguments) -> keyOf(service, tool, arguments) }

  fun isFresh(record: ToolResultRecord, nowUtcMs: Long): Boolean = nowUtcMs < record.freshUntilUtcMs

  /** The next [RESET_HOUR] local after [fetchedUtcMs]: never more than 24 hours away. */
  fun freshUntil(fetchedUtcMs: Long, tzOffsetMinutes: Int): Long {
    val offset = tzOffsetMinutes * MIN_MS
    val local = fetchedUtcMs + offset
    val reset = local - local.mod(DAY_MS) + RESET_HOUR * HOUR_MS
    return (if (local < reset) reset else reset + DAY_MS) - offset
  }

  /**
   * The kept result that may answer [request] with no card and no network. Only a tool the server marks read-only: asking
   * "สร้างใบสั่งซื้อ" twice must run it twice, never report the first run as done.
   */
  fun recall(skill: SkillRecord, request: ActionRequestInput, memory: ToolMemoryRepository, nowUtcMs: Long): ToolResultRecord? {
    if (!skill.readOnly) return null
    return keyOf(request)?.let(memory::find)?.takeIf { isFresh(it, nowUtcMs) }
  }

  /** Keeps a confirmed result when [recall] may use it, and removes what passed [KEEP_RAW_MS]. Null = not kept. */
  fun remember(
    skill: SkillRecord,
    request: ActionRequestInput,
    text: String,
    memory: ToolMemoryRepository,
    nowUtcMs: Long,
    tzOffsetMinutes: Int,
  ): ToolResultRecord? {
    if (!skill.readOnly || text.isBlank()) return null
    val (service, tool, arguments) = parts(request) ?: return null
    // the notice promises 30 days, and a phone that rarely charges may not run the nightly job
    memory.deleteFetchedBefore(nowUtcMs - KEEP_RAW_MS)
    return ToolResultRecord(keyOf(service, tool, arguments), service, tool, arguments, text, nowUtcMs, freshUntil(nowUtcMs, tzOffsetMinutes))
      .also(memory::put)
  }

  fun sourceOf(record: ToolResultRecord, fromMemory: Boolean): ToolSource =
    ToolSource(record.key, record.service, record.fetchedUtcMs, record.freshUntilUtcMs, fromMemory)

  private fun parts(request: ActionRequestInput): Triple<String, String, Map<String, String>>? {
    if (request.action != SkillActions.MCP_ACTION) return null
    val service = request.arguments[SkillActions.KEY_SERVER]?.takeIf { it.isNotBlank() } ?: return null
    val tool = request.arguments[SkillActions.KEY_TOOL]?.takeIf { it.isNotBlank() } ?: return null
    return Triple(service, tool, request.arguments - SkillActions.KEY_SERVER - SkillActions.KEY_TOOL)
  }

  private const val SEPARATOR = "\u0001"
}
