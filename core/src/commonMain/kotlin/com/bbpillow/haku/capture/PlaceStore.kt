package com.bbpillow.haku.capture

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/** All place-library SQL. Not thread-safe: callers serialize access. */
class PlaceStore(private val db: SQLiteConnection) : PlaceRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS places(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, " +
        "label TEXT, category TEXT NOT NULL, confirmed INTEGER NOT NULL, auto_name TEXT)",
    )
    if (!hasColumn("auto_name")) db.execSQL("ALTER TABLE places ADD COLUMN auto_name TEXT")
  }

  override fun list(): List<PlaceRecord> =
    db.prepare("SELECT id, lat, lon, label, category, confirmed, auto_name FROM places ORDER BY id").use { st ->
      buildList {
        while (st.step()) {
          add(
            PlaceRecord(
              st.getText(0), st.getDouble(1), st.getDouble(2),
              if (st.isNull(3)) null else st.getText(3),
              PlaceCategory.valueOf(st.getText(4)), st.getLong(5) == 1L,
              if (st.isNull(6)) null else st.getText(6),
            ),
          )
        }
      }
    }

  override fun addNew(places: List<PlaceRecord>): Int = inTransaction {
    places.count { p ->
      run(
        "INSERT OR IGNORE INTO places(id, lat, lon, label, category, confirmed) VALUES(?,?,?,?,?,?)",
      ) {
        it.bindText(1, p.id)
        it.bindDouble(2, p.lat)
        it.bindDouble(3, p.lon)
        if (p.label == null) it.bindNull(4) else it.bindText(4, p.label)
        it.bindText(5, p.category.name)
        it.bindLong(6, if (p.confirmed) 1 else 0)
      }
      changes() > 0
    }
  }

  override fun confirm(id: String, label: String, category: PlaceCategory): Boolean = inTransaction {
    run("UPDATE places SET label = ?, category = ?, confirmed = 1 WHERE id = ?") {
      it.bindText(1, label)
      it.bindText(2, category.name)
      it.bindText(3, id)
    }
    changes() > 0
  }

  override fun delete(id: String): Boolean = inTransaction {
    run("DELETE FROM places WHERE id = ?") { it.bindText(1, id) }
    changes() > 0
  }

  override fun setAutoName(id: String, name: String): Boolean = inTransaction {
    run("UPDATE places SET auto_name = ? WHERE id = ? AND auto_name IS NULL") {
      it.bindText(1, name)
      it.bindText(2, id)
    }
    changes() > 0
  }

  override fun deleteAll() { inTransaction { run("DELETE FROM places") {} } }

  private fun hasColumn(column: String): Boolean = db.prepare("PRAGMA table_info(places)").use { st ->
    var found = false
    while (st.step()) if (st.getText(1) == column) found = true
    found
  }

  private fun changes(): Long = db.prepare("SELECT changes()").use { it.step(); it.getLong(0) }

  private fun run(sql: String, bind: (SQLiteStatement) -> Unit) = db.prepare(sql).use { bind(it); it.step() }

  private fun <T> inTransaction(block: () -> T): T {
    db.execSQL("BEGIN")
    try {
      return block().also { db.execSQL("COMMIT") }
    } catch (e: Throwable) {
      db.execSQL("ROLLBACK")
      throw e
    }
  }
}
