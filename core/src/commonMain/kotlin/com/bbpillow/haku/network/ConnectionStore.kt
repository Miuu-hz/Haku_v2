package com.bbpillow.haku.network

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** All connection-log SQL (same DB as capture). Not thread-safe: callers serialize access. */
class ConnectionStore(private val db: SQLiteConnection) : ConnectionRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS connections(at INTEGER NOT NULL, host TEXT NOT NULL, path TEXT NOT NULL, " +
        "purpose TEXT NOT NULL, bytes_in INTEGER, bytes_out INTEGER, result TEXT NOT NULL)",
    )
  }

  override fun add(record: ConnectionRecord) {
    db.prepare("DELETE FROM connections WHERE at < ?").use {
      it.bindLong(1, record.atEpochMs - ConnectionRepository.RETENTION_MS)
      it.step()
    }
    db.prepare("INSERT INTO connections(at, host, path, purpose, bytes_in, bytes_out, result) VALUES(?,?,?,?,?,?,?)").use { st ->
      st.bindLong(1, record.atEpochMs)
      st.bindText(2, record.host)
      st.bindText(3, record.path)
      st.bindText(4, record.purpose.name)
      record.bytesIn?.let { st.bindLong(5, it) } ?: st.bindNull(5)
      record.bytesOut?.let { st.bindLong(6, it) } ?: st.bindNull(6)
      st.bindText(7, record.result)
      st.step()
    }
  }

  override fun since(fromEpochMs: Long): List<ConnectionRecord> =
    db.prepare(
      "SELECT at, host, path, purpose, bytes_in, bytes_out, result FROM connections WHERE at >= ? ORDER BY at DESC, rowid DESC",
    ).use { st ->
      st.bindLong(1, fromEpochMs)
      buildList {
        while (st.step()) {
          // a purpose removed in a later version: skip the row rather than crash the screen
          val purpose = ConnectionPurpose.entries.firstOrNull { it.name == st.getText(3) } ?: continue
          add(
            ConnectionRecord(
              st.getLong(0), st.getText(1), st.getText(2), purpose,
              if (st.isNull(4)) null else st.getLong(4),
              if (st.isNull(5)) null else st.getLong(5),
              st.getText(6),
            ),
          )
        }
      }
    }

  override fun clear() = db.execSQL("DELETE FROM connections")
}
