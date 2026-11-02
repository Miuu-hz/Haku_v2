package com.bbpillow.haku.action

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** All action-audit SQL (same DB as capture). Not thread-safe: callers serialize access. */
class AuditStore(private val db: SQLiteConnection) : AuditRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS action_audit(" +
        "proposal_id TEXT NOT NULL, action TEXT NOT NULL, origin TEXT NOT NULL, " +
        "level TEXT NOT NULL, status TEXT NOT NULL, title TEXT NOT NULL, at_utc_ms INTEGER NOT NULL)",
    )
  }

  override fun append(record: AuditRecord) {
    db.prepare("DELETE FROM action_audit WHERE at_utc_ms < ?").use {
      it.bindLong(1, record.atUtcMs - RETENTION_MS)
      it.step()
    }
    db.prepare(
      "INSERT INTO action_audit(proposal_id, action, origin, level, status, title, at_utc_ms) VALUES(?,?,?,?,?,?,?)",
    ).use { st ->
      st.bindText(1, record.proposalId)
      st.bindText(2, record.action)
      st.bindText(3, record.origin.name)
      st.bindText(4, record.level.name)
      st.bindText(5, record.status.name)
      st.bindText(6, record.title)
      st.bindLong(7, record.atUtcMs)
      st.step()
    }
  }

  override fun recent(limit: Int): List<AuditRecord> {
    if (limit <= 0) return emptyList()
    return db.prepare(
      "SELECT proposal_id, action, origin, level, status, title, at_utc_ms " +
        "FROM action_audit ORDER BY at_utc_ms DESC, rowid DESC LIMIT ?",
    ).use { st ->
      st.bindLong(1, limit.toLong())
      buildList {
        while (st.step()) {
          val origin = ActionOrigin.entries.firstOrNull { it.name == st.getText(2) } ?: continue
          val level = PermissionLevel.entries.firstOrNull { it.name == st.getText(3) } ?: continue
          val status = ActionStatus.entries.firstOrNull { it.name == st.getText(4) } ?: continue
          add(
            AuditRecord(
              proposalId = st.getText(0),
              action = st.getText(1),
              origin = origin,
              level = level,
              status = status,
              title = st.getText(5),
              atUtcMs = st.getLong(6),
            ),
          )
        }
      }
    }
  }

  override fun clear() = db.execSQL("DELETE FROM action_audit")

  companion object {
    const val RETENTION_DAYS = 90L
    const val RETENTION_MS = RETENTION_DAYS * 86_400_000L
  }
}
