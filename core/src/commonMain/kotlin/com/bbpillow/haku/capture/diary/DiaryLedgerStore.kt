package com.bbpillow.haku.capture.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** All diary-ledger SQL: table `diary_lines`. Not thread-safe; encryption comes from the driver, as in CaptureEventStore. */
class DiaryLedgerStore(private val db: SQLiteConnection) : DiaryLedgerRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS diary_lines(id TEXT PRIMARY KEY, memory_day TEXT NOT NULL, record_id TEXT NOT NULL, deleted INTEGER NOT NULL, type_key TEXT NOT NULL DEFAULT '')",
    )
    if (!hasColumn("diary_lines", "type_key")) db.execSQL("ALTER TABLE diary_lines ADD COLUMN type_key TEXT NOT NULL DEFAULT ''")
    db.execSQL("CREATE TABLE IF NOT EXISTS diary_type_strikes(type_key TEXT PRIMARY KEY, strikes INTEGER NOT NULL)")
    db.execSQL("CREATE INDEX IF NOT EXISTS diary_lines_record ON diary_lines(memory_day, record_id)")
  }

  override fun find(lineId: String): DiaryLedgerEntry? =
    one("SELECT id, memory_day, record_id, deleted FROM diary_lines WHERE id = ?") { it.bindText(1, lineId) }

  override fun findByRecord(memoryDay: String, recordId: String): DiaryLedgerEntry? =
    one("SELECT id, memory_day, record_id, deleted FROM diary_lines WHERE memory_day = ? AND record_id = ?") {
      it.bindText(1, memoryDay)
      it.bindText(2, recordId)
    }

  // INSERT OR IGNORE: a tombstone is never overwritten by a later write
  override fun recordWritten(lineId: String, memoryDay: String, memoryRecordId: String, typeKey: String) {
    run("INSERT OR IGNORE INTO diary_lines(id, memory_day, record_id, deleted, type_key) VALUES(?,?,?,0,?)") {
      it.bindText(1, lineId)
      it.bindText(2, memoryDay)
      it.bindText(3, memoryRecordId)
      it.bindText(4, typeKey)
    }
  }

  override fun markDeleted(lineId: String, countsAsStrike: Boolean) {
    if (find(lineId)?.deleted == true) return // a repeat delete is not a second strike
    run("INSERT OR IGNORE INTO diary_lines(id, memory_day, record_id, deleted) VALUES(?,'','',1)") { it.bindText(1, lineId) }
    run("UPDATE diary_lines SET deleted = 1 WHERE id = ?") { it.bindText(1, lineId) }
    val type = typeKeyOf(lineId)
    if (countsAsStrike && type.isNotEmpty()) {
      run("INSERT INTO diary_type_strikes(type_key, strikes) VALUES(?,1) ON CONFLICT(type_key) DO UPDATE SET strikes = strikes + 1") {
        it.bindText(1, type)
      }
    }
  }

  override fun restore(lineId: String, memoryDay: String, memoryRecordId: String): Boolean {
    if (find(lineId)?.deleted != true) return false
    run("UPDATE diary_lines SET deleted = 0, memory_day = ?, record_id = ? WHERE id = ?") {
      it.bindText(1, memoryDay)
      it.bindText(2, memoryRecordId)
      it.bindText(3, lineId)
    }
    run("UPDATE diary_type_strikes SET strikes = MAX(strikes - 1, 0) WHERE type_key = ?") { it.bindText(1, typeKeyOf(lineId)) }
    return true
  }

  override fun isSuppressed(typeKey: String): Boolean =
    db.prepare("SELECT strikes FROM diary_type_strikes WHERE type_key = ?").use { st ->
      st.bindText(1, typeKey)
      st.step() && st.getLong(0) >= DiaryThresholds.SUPPRESS_AFTER_DELETES
    }

  override fun suppressedTypes(): List<SuppressedLineType> =
    db.prepare("SELECT type_key, strikes FROM diary_type_strikes WHERE strikes >= ? ORDER BY type_key").use { st ->
      st.bindLong(1, DiaryThresholds.SUPPRESS_AFTER_DELETES.toLong())
      buildList { while (st.step()) add(SuppressedLineType(st.getText(0), st.getLong(1).toInt())) }
    }

  override fun unsuppress(typeKey: String) {
    run("DELETE FROM diary_type_strikes WHERE type_key = ?") { it.bindText(1, typeKey) }
  }

  private fun typeKeyOf(lineId: String): String =
    db.prepare("SELECT type_key FROM diary_lines WHERE id = ?").use { st ->
      st.bindText(1, lineId)
      if (st.step()) st.getText(0) else ""
    }

  private fun hasColumn(table: String, column: String): Boolean = db.prepare("PRAGMA table_info($table)").use { st ->
    var found = false
    while (st.step()) if (st.getText(1) == column) found = true
    found
  }

  private fun one(sql: String, bind: (SQLiteStatement) -> Unit): DiaryLedgerEntry? = db.prepare(sql).use { st ->
    bind(st)
    if (st.step()) DiaryLedgerEntry(st.getText(0), st.getText(1), st.getText(2), st.getLong(3) != 0L) else null
  }

  private fun run(sql: String, bind: (SQLiteStatement) -> Unit) = db.prepare(sql).use { bind(it); it.step() }
}
