package com.bbpillow.haku.action

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * SQLite store for pending appointment offers. Table `pending_offers`.
 * Not thread-safe: callers serialize access on the DB dispatcher.
 * No logging.
 */
class PendingOfferStore(private val db: SQLiteConnection) : PendingOfferRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS pending_offers(" +
        "id TEXT PRIMARY KEY, " +
        "dedupe_key TEXT NOT NULL UNIQUE, " +
        "title TEXT NOT NULL, " +
        "detail TEXT NOT NULL, " +
        "start_ms INTEGER NOT NULL, " +
        "end_ms INTEGER NOT NULL, " +
        "all_day INTEGER NOT NULL, " +
        "origin TEXT NOT NULL, " +
        "app_name TEXT, " +
        "warning TEXT, " +
        "created_ms INTEGER NOT NULL, " +
        "expires_ms INTEGER NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_offers_expires_ms ON pending_offers(expires_ms)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_offers_start_ms ON pending_offers(start_ms)")
  }

  override fun active(nowUtcMs: Long): List<PendingOfferRecord> =
    query(
      "SELECT id, dedupe_key, title, detail, start_ms, end_ms, all_day, origin, app_name, warning, created_ms, expires_ms " +
        "FROM pending_offers WHERE expires_ms > ? ORDER BY start_ms, id",
      listOf(nowUtcMs),
    ) { mapRow(it) }

  override fun find(id: String): PendingOfferRecord? =
    query(
      "SELECT id, dedupe_key, title, detail, start_ms, end_ms, all_day, origin, app_name, warning, created_ms, expires_ms " +
        "FROM pending_offers WHERE id = ?",
      listOf(id),
    ) { mapRow(it) }.firstOrNull()

  override fun put(record: PendingOfferRecord) {
    exec(
      "INSERT OR REPLACE INTO pending_offers(" +
        "id, dedupe_key, title, detail, start_ms, end_ms, all_day, origin, app_name, warning, created_ms, expires_ms" +
        ") VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
      listOf(
        record.id,
        record.dedupeKey,
        record.title,
        record.detail,
        record.startUtcMs,
        record.endUtcMs,
        record.allDay,
        record.origin.name,
        record.appName,
        record.warning,
        record.createdUtcMs,
        record.expiresUtcMs,
      ),
    )
  }

  override fun delete(id: String) {
    exec("DELETE FROM pending_offers WHERE id = ?", listOf(id))
  }

  override fun deleteExpired(nowUtcMs: Long): Int = inTransaction {
    exec("DELETE FROM pending_offers WHERE expires_ms <= ?", listOf(nowUtcMs))
    changes().toInt()
  }

  override fun clear() = inTransaction {
    exec("DELETE FROM pending_offers", emptyList())
  }

  private fun parseOrigin(name: String): ActionOrigin =
    ActionOrigin.entries.firstOrNull { it.name == name } ?: ActionOrigin.NOTIFICATION_RULE

  private fun mapRow(st: SQLiteStatement) = PendingOfferRecord(
    id = st.getText(0),
    dedupeKey = st.getText(1),
    title = st.getText(2),
    detail = st.getText(3),
    startUtcMs = st.getLong(4),
    endUtcMs = st.getLong(5),
    allDay = st.getLong(6) != 0L,
    origin = parseOrigin(st.getText(7)),
    appName = if (st.isNull(8)) null else st.getText(8),
    warning = if (st.isNull(9)) null else st.getText(9),
    createdUtcMs = st.getLong(10),
    expiresUtcMs = st.getLong(11),
  )

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
