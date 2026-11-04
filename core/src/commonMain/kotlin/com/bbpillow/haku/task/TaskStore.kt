package com.bbpillow.haku.task

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * SQLite store for tasks. Table `tasks`.
 * Not thread-safe: callers serialize access on the DB dispatcher.
 * Holds personal data: no logging.
 */
class TaskStore(private val db: SQLiteConnection) : TaskRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS tasks(" +
        "id TEXT PRIMARY KEY, " +
        "title TEXT NOT NULL, " +
        "due_ms INTEGER, " +
        "important INTEGER NOT NULL, " +
        "created_ms INTEGER NOT NULL, " +
        "done_ms INTEGER)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_created_ms ON tasks(created_ms)")
  }

  override fun all(): List<TaskRecord> =
    query(
      "SELECT id, title, due_ms, important, created_ms, done_ms FROM tasks ORDER BY created_ms, id",
      emptyList(),
    ) { mapRow(it) }

  override fun find(id: String): TaskRecord? =
    query(
      "SELECT id, title, due_ms, important, created_ms, done_ms FROM tasks WHERE id = ?",
      listOf(id),
    ) { mapRow(it) }.firstOrNull()

  override fun put(record: TaskRecord) {
    exec(
      "INSERT OR REPLACE INTO tasks(id, title, due_ms, important, created_ms, done_ms) VALUES(?, ?, ?, ?, ?, ?)",
      listOf(
        record.id,
        record.title,
        record.dueUtcMs,
        record.important,
        record.createdUtcMs,
        record.doneUtcMs,
      ),
    )
  }

  override fun delete(id: String) {
    exec("DELETE FROM tasks WHERE id = ?", listOf(id))
  }

  override fun deleteDoneBefore(cutoffUtcMs: Long): Int = inTransaction {
    exec("DELETE FROM tasks WHERE done_ms IS NOT NULL AND done_ms < ?", listOf(cutoffUtcMs))
    changes().toInt()
  }

  override fun clear() = inTransaction {
    exec("DELETE FROM tasks", emptyList())
  }

  private fun mapRow(st: SQLiteStatement) = TaskRecord(
    id = st.getText(0),
    title = st.getText(1),
    dueUtcMs = if (st.isNull(2)) null else st.getLong(2),
    important = st.getLong(3) != 0L,
    createdUtcMs = st.getLong(4),
    doneUtcMs = if (st.isNull(5)) null else st.getLong(5),
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
