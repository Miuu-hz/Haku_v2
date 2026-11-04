package com.bbpillow.haku.skill

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * SQLite store for confirmed tool results. Table `tool_results`.
 * Not thread-safe: callers serialize access on the DB dispatcher.
 * Encryption at rest comes from the SQLite driver opening [db].
 * Holds sensitive business data: no logging.
 */
class ToolMemoryStore(private val db: SQLiteConnection) : ToolMemoryRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS tool_results(" +
        "key TEXT PRIMARY KEY, " +
        "service TEXT NOT NULL, " +
        "tool TEXT NOT NULL, " +
        "arguments TEXT NOT NULL, " +
        "text TEXT NOT NULL, " +
        "fetched_ms INTEGER NOT NULL, " +
        "fresh_until_ms INTEGER NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_tool_results_fetched_ms ON tool_results(fetched_ms)")
  }

  override fun find(key: String): ToolResultRecord? =
    query(
      "SELECT key, service, tool, arguments, text, fetched_ms, fresh_until_ms FROM tool_results WHERE key = ?",
      listOf(key),
    ) { st ->
      ToolResultRecord(
        key = st.getText(0),
        service = st.getText(1),
        tool = st.getText(2),
        arguments = decodeArguments(st.getText(3)),
        text = st.getText(4),
        fetchedUtcMs = st.getLong(5),
        freshUntilUtcMs = st.getLong(6),
      )
    }.firstOrNull()

  override fun put(record: ToolResultRecord) {
    exec(
      "INSERT OR REPLACE INTO tool_results(key, service, tool, arguments, text, fetched_ms, fresh_until_ms) " +
        "VALUES(?, ?, ?, ?, ?, ?, ?)",
      listOf(
        record.key,
        record.service,
        record.tool,
        encodeArguments(record.arguments),
        record.text,
        record.fetchedUtcMs,
        record.freshUntilUtcMs,
      ),
    )
  }

  override fun expireAll(nowUtcMs: Long) {
    exec(
      "UPDATE tool_results SET fresh_until_ms = ? WHERE fresh_until_ms > ?",
      listOf(nowUtcMs, nowUtcMs),
    )
  }

  override fun deleteFetchedBefore(cutoffUtcMs: Long): Int = inTransaction {
    exec("DELETE FROM tool_results WHERE fetched_ms < ?", listOf(cutoffUtcMs))
    changes().toInt()
  }

  override fun clear() = inTransaction {
    exec("DELETE FROM tool_results", emptyList())
  }

  private fun changes(): Long = db.prepare("SELECT changes()").use { it.step(); it.getLong(0) }

  private fun <T> inTransaction(block: () -> T): T {
    db.execSQL("BEGIN")
    try {
      return block().also { db.execSQL("COMMIT") }
    } catch (e: Throwable) {
      db.execSQL("ROLLBACK")
      throw e
    }
  }

  private fun exec(sql: String, args: List<Any?>) {
    db.prepare(sql).use { bind(it, args); it.step() }
  }

  private fun <T> query(sql: String, args: List<Any?>, row: (SQLiteStatement) -> T): List<T> =
    db.prepare(sql).use { st ->
      bind(st, args)
      buildList { while (st.step()) add(row(st)) }
    }

  private fun bind(st: SQLiteStatement, args: List<Any?>) = args.forEachIndexed { i, a ->
    when (a) {
      null -> st.bindNull(i + 1)
      is Long -> st.bindLong(i + 1, a)
      is Int -> st.bindLong(i + 1, a.toLong())
      is Boolean -> st.bindLong(i + 1, if (a) 1L else 0L)
      else -> st.bindText(i + 1, a.toString())
    }
  }
}

// Plain text form of string slots. Percent-escaped so any value round-trips; never echoed in an error, it is business data.
internal fun encodeArguments(arguments: Map<String, String>): String =
  arguments.entries.sortedBy { it.key }.joinToString("&") { "${escapeArgument(it.key)}=${escapeArgument(it.value)}" }

internal fun decodeArguments(encoded: String): Map<String, String> =
  if (encoded.isEmpty()) emptyMap()
  else encoded.split("&").associate { pair ->
    val (key, value) = pair.split("=", limit = 2)
    unescapeArgument(key) to unescapeArgument(value)
  }

private fun escapeArgument(s: String) = s.replace("%", "%25").replace("&", "%26").replace("=", "%3D")

private fun unescapeArgument(s: String) = s.replace("%3D", "=").replace("%26", "&").replace("%25", "%")
