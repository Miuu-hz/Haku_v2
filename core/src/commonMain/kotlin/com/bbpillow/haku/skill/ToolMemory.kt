package com.bbpillow.haku.skill

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

/** Where an answer's tool result came from, for the chip under the answer. */
data class ToolSource(val service: String, val fetchedUtcMs: Long, val freshUntilUtcMs: Long, val fromMemory: Boolean)

/** Sensitive: real business data lives here. Same encrypted database as the diary; forget-everything calls [clear]. */
interface ToolMemoryRepository {
  fun find(key: String): ToolResultRecord?

  /** Replaces any record with the same key. */
  fun put(record: ToolResultRecord)

  /** The nightly job: every record stops being fresh now. The text stays until [deleteFetchedBefore] removes it. */
  fun expireAll(nowUtcMs: Long)

  /** Returns how many records were removed. */
  fun deleteFetchedBefore(cutoffUtcMs: Long): Int

  fun clear()
}

object ToolMemory {
  const val FRESH_MS = 24L * 60 * 60 * 1000
  const val KEEP_RAW_MS = 30L * 24 * 60 * 60 * 1000

  /**
   * Exact match only: an answer about shop A must never serve shop B, so values are compared as written apart from
   * surrounding spaces. A meaning-based match waits until it is measured and guarded.
   */
  fun keyOf(service: String, tool: String, arguments: Map<String, String>): String =
    (listOf(service.lowercase(), tool) + arguments.entries.sortedBy { it.key }.map { "${it.key}=${it.value.trim()}" })
      .joinToString(SEPARATOR)

  fun isFresh(record: ToolResultRecord, nowUtcMs: Long): Boolean = nowUtcMs < record.freshUntilUtcMs

  private const val SEPARATOR = "\u0001"
}
