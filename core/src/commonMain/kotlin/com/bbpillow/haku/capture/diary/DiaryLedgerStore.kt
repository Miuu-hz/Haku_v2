package com.bbpillow.haku.capture.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** All diary-ledger SQL: table `diary_lines`. Not thread-safe; encryption comes from the driver, as in CaptureEventStore. */
class DiaryLedgerStore(private val db: SQLiteConnection) : DiaryLedgerRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS diary_lines(id TEXT PRIMARY KEY, memory_day TEXT NOT NULL, record_id TEXT NOT NULL, deleted INTEGER NOT NULL)",
    )
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
  override fun recordWritten(lineId: String, memoryDay: String, memoryRecordId: String) {
    run("INSERT OR IGNORE INTO diary_lines(id, memory_day, record_id, deleted) VALUES(?,?,?,0)") {
      it.bindText(1, lineId)
      it.bindText(2, memoryDay)
      it.bindText(3, memoryRecordId)
    }
  }

  override fun markDeleted(lineId: String) {
    run("INSERT OR IGNORE INTO diary_lines(id, memory_day, record_id, deleted) VALUES(?,'','',1)") { it.bindText(1, lineId) }
    run("UPDATE diary_lines SET deleted = 1 WHERE id = ?") { it.bindText(1, lineId) }
  }

  private fun one(sql: String, bind: (SQLiteStatement) -> Unit): DiaryLedgerEntry? = db.prepare(sql).use { st ->
    bind(st)
    if (st.step()) DiaryLedgerEntry(st.getText(0), st.getText(1), st.getText(2), st.getLong(3) != 0L) else null
  }

  private fun run(sql: String, bind: (SQLiteStatement) -> Unit) = db.prepare(sql).use { bind(it); it.step() }
}
