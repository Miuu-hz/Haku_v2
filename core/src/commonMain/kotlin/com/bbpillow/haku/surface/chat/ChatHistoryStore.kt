package com.bbpillow.haku.surface.chat

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef

data class ChatSessionSummary(val id: Long, val title: String, val lastAtMs: Long, val turns: Int, val pinned: Boolean)

class ChatHistoryStore(private val db: SQLiteConnection) {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS chat_sessions(" +
        "id INTEGER PRIMARY KEY, " +
        "title TEXT NOT NULL, " +
        "last_at_ms INTEGER NOT NULL, " +
        "pinned INTEGER NOT NULL DEFAULT 0)",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS chat_turns(" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        "session_id INTEGER NOT NULL, " +
        "at_ms INTEGER NOT NULL, " +
        "question TEXT NOT NULL, " +
        "answer TEXT NOT NULL, " +
        "source TEXT NOT NULL, " +
        "stopped INTEGER NOT NULL DEFAULT 0)",
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS chat_citations(" +
        "turn_id INTEGER NOT NULL, " +
        "position INTEGER NOT NULL, " +
        "label TEXT NOT NULL, " +
        "day TEXT, " +
        "text TEXT NOT NULL, " +
        "at_ms INTEGER NOT NULL, " +
        "level TEXT NOT NULL, " +
        "grp TEXT NOT NULL, " +
        "mem_id TEXT NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_turns_session_id ON chat_turns(session_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_citations_turn_id ON chat_citations(turn_id)")
  }

  /** Saves a finished answer under its chat; a chat is created by its first answer, titled from that question. Blank answers are not saved. Also runs [purge]. */
  fun append(sessionId: Long, atMs: Long, answer: ChatAnswerOutput) {
    if (answer.text.isNotBlank()) {
      inTransaction {
        val existingLast = query("SELECT last_at_ms FROM chat_sessions WHERE id = ?", listOf(sessionId)) { it.getLong(0) }.firstOrNull()
        if (existingLast == null) {
          val title = answer.question.take(TITLE_CHARS)
          exec("INSERT INTO chat_sessions(id, title, last_at_ms, pinned) VALUES(?, ?, ?, 0)", listOf(sessionId, title, atMs))
        } else {
          val newest = maxOf(existingLast, atMs)
          exec("UPDATE chat_sessions SET last_at_ms = ? WHERE id = ?", listOf(newest, sessionId))
        }

        exec(
          "INSERT INTO chat_turns(session_id, at_ms, question, answer, source, stopped) VALUES(?, ?, ?, ?, ?, ?)",
          listOf(sessionId, atMs, answer.question, answer.text, answer.source.name, if (answer.stopped) 1L else 0L),
        )
        val turnId = query("SELECT last_insert_rowid()", emptyList()) { it.getLong(0) }.single()

        answer.citations.forEachIndexed { pos, c ->
          exec(
            "INSERT INTO chat_citations(turn_id, position, label, day, text, at_ms, level, grp, mem_id) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(turnId, pos.toLong(), c.label, c.day, c.text, c.atEpochMs, c.source.level.name, c.source.group, c.source.id),
          )
        }
      }
    }
    purge(atMs)
  }

  /** Newest activity first. */
  fun sessions(): List<ChatSessionSummary> = query(
    "SELECT s.id, s.title, s.last_at_ms, count(t.id), s.pinned " +
      "FROM chat_sessions s " +
      "LEFT JOIN chat_turns t ON s.id = t.session_id " +
      "GROUP BY s.id, s.title, s.last_at_ms, s.pinned " +
      "ORDER BY s.last_at_ms DESC, s.id DESC",
    emptyList(),
  ) { st ->
    ChatSessionSummary(
      id = st.getLong(0),
      title = st.getText(1),
      lastAtMs = st.getLong(2),
      turns = st.getLong(3).toInt(),
      pinned = st.getLong(4) != 0L,
    )
  }

  /** Oldest first, with citations in their original order; empty for an unknown id. */
  fun answers(sessionId: Long): List<ChatAnswerOutput> {
    val turnRows = query(
      "SELECT id, question, answer, source, stopped FROM chat_turns WHERE session_id = ? ORDER BY id ASC",
      listOf(sessionId),
    ) { st ->
      TurnRow(
        id = st.getLong(0),
        question = st.getText(1),
        answer = st.getText(2),
        sourceName = st.getText(3),
        stopped = st.getLong(4) != 0L,
      )
    }
    if (turnRows.isEmpty()) return emptyList()

    val citationsByTurn = query(
      "SELECT turn_id, label, day, text, at_ms, level, grp, mem_id FROM chat_citations " +
        "WHERE turn_id IN (SELECT id FROM chat_turns WHERE session_id = ?) ORDER BY turn_id ASC, position ASC",
      listOf(sessionId),
    ) { st ->
      CitationRow(
        turnId = st.getLong(0),
        label = st.getText(1),
        day = if (st.isNull(2)) null else st.getText(2),
        text = st.getText(3),
        atMs = st.getLong(4),
        levelName = st.getText(5),
        grp = st.getText(6),
        memId = st.getText(7),
      )
    }.groupBy { it.turnId }

    return buildList {
      for (turn in turnRows) {
        val source = ChatAnswerSource.entries.firstOrNull { it.name == turn.sourceName } ?: continue
        val citations = buildList {
          for (c in citationsByTurn[turn.id].orEmpty()) {
            val level = MemoryLevel.entries.firstOrNull { it.name == c.levelName } ?: continue
            add(
              ChatCitation(
                label = c.label,
                day = c.day,
                text = c.text,
                atEpochMs = c.atMs,
                source = MemoryRef(level, c.grp, c.memId),
              ),
            )
          }
        }
        add(
          ChatAnswerOutput(
            question = turn.question,
            text = turn.answer,
            citations = citations,
            source = source,
            stopped = turn.stopped,
          ),
        )
      }
    }
  }

  fun setPinned(sessionId: Long, pinned: Boolean) {
    exec("UPDATE chat_sessions SET pinned = ? WHERE id = ?", listOf(if (pinned) 1L else 0L, sessionId))
  }

  /** Removes the chat and everything under it. */
  fun delete(sessionId: Long) = inTransaction {
    deleteInternal(sessionId)
  }

  /** Removes unpinned chats whose last activity is older than RETENTION_MS. Returns how many chats went. */
  fun purge(nowMs: Long): Int = inTransaction {
    val cutoff = nowMs - RETENTION_MS
    val expiredIds = query("SELECT id FROM chat_sessions WHERE pinned = 0 AND last_at_ms < ?", listOf(cutoff)) { it.getLong(0) }
    for (id in expiredIds) {
      deleteInternal(id)
    }
    expiredIds.size
  }

  /** Everything, for "forget everything". */
  fun clear() = inTransaction {
    exec("DELETE FROM chat_citations", emptyList())
    exec("DELETE FROM chat_turns", emptyList())
    exec("DELETE FROM chat_sessions", emptyList())
  }

  private fun deleteInternal(sessionId: Long) {
    exec("DELETE FROM chat_citations WHERE turn_id IN (SELECT id FROM chat_turns WHERE session_id = ?)", listOf(sessionId))
    exec("DELETE FROM chat_turns WHERE session_id = ?", listOf(sessionId))
    exec("DELETE FROM chat_sessions WHERE id = ?", listOf(sessionId))
  }

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

  private data class TurnRow(
    val id: Long,
    val question: String,
    val answer: String,
    val sourceName: String,
    val stopped: Boolean,
  )

  private data class CitationRow(
    val turnId: Long,
    val label: String,
    val day: String?,
    val text: String,
    val atMs: Long,
    val levelName: String,
    val grp: String,
    val memId: String,
  )

  companion object {
    const val RETENTION_DAYS = 7L
    const val RETENTION_MS = RETENTION_DAYS * 86_400_000L
    const val TITLE_CHARS = 60
  }
}

/** The chat as it stood after these answers, to carry on from. */
fun List<ChatAnswerOutput>.toSession(id: Long): ChatSessionState = fold(ChatSessionState(id)) { s, a -> s.after(a) }
