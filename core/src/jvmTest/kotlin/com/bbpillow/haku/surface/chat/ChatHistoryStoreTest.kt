package com.bbpillow.haku.surface.chat

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.thinking.ChatExchange
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun openDb(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also(opened::add)

  private fun count(db: SQLiteConnection, table: String): Long =
    db.prepare("SELECT count(*) FROM $table").use { it.step(); it.getLong(0) }

  @Test fun appendThenAnswersRoundTripsAllFieldsAndCitationsInOrderWithNullDay() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val answer = ChatAnswerOutput(
      question = "เมื่อวานไปไหนมา",
      text = "ไปสยามมาครับ",
      citations = listOf(
        ChatCitation("ไดอารี", "2026-11-03", "10:00 ไปสยาม", 1700000000000L, MemoryRef(MemoryLevel.DAILY, "2026-11-03", "m1")),
        ChatCitation("ความจำ", null, "ชอบไปสยาม", 1690000000000L, MemoryRef(MemoryLevel.CORE, "places", "m2")),
      ),
      source = ChatAnswerSource.MODEL,
      stopped = true,
    )
    store.append(sessionId = 1L, atMs = 1000L, answer = answer)

    val restored = store.answers(1L)
    assertEquals(1, restored.size)
    val actual = restored.single()
    assertEquals("เมื่อวานไปไหนมา", actual.question)
    assertEquals("ไปสยามมาครับ", actual.text)
    assertEquals(ChatAnswerSource.MODEL, actual.source)
    assertTrue(actual.stopped)
    assertEquals(2, actual.citations.size)

    assertEquals("ไดอารี", actual.citations[0].label)
    assertEquals("2026-11-03", actual.citations[0].day)
    assertEquals("10:00 ไปสยาม", actual.citations[0].text)
    assertEquals(1700000000000L, actual.citations[0].atEpochMs)
    assertEquals(MemoryRef(MemoryLevel.DAILY, "2026-11-03", "m1"), actual.citations[0].source)

    assertEquals("ความจำ", actual.citations[1].label)
    assertEquals(null, actual.citations[1].day)
    assertEquals("ชอบไปสยาม", actual.citations[1].text)
    assertEquals(1690000000000L, actual.citations[1].atEpochMs)
    assertEquals(MemoryRef(MemoryLevel.CORE, "places", "m2"), actual.citations[1].source)
  }

  @Test fun secondAnswerKeepsTitleAndMovesLastAtMs() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans1 = ChatAnswerOutput("คำถามแรก", "ตอบ 1", emptyList(), ChatAnswerSource.GENERAL)
    val ans2 = ChatAnswerOutput("คำถามที่สอง", "ตอบ 2", emptyList(), ChatAnswerSource.GENERAL)
    store.append(1L, 1000L, ans1)
    store.append(1L, 2500L, ans2)

    val session = store.sessions().single()
    assertEquals(1L, session.id)
    assertEquals("คำถามแรก", session.title)
    assertEquals(2500L, session.lastAtMs)
    assertEquals(2, session.turns)
  }

  @Test fun blankTextIsNotSaved() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val blank = ChatAnswerOutput("คำถาม", "   ", emptyList(), ChatAnswerSource.GENERAL)
    store.append(1L, 1000L, blank)

    assertTrue(store.sessions().isEmpty())
    assertTrue(store.answers(1L).isEmpty())
    assertEquals(0L, count(db, "chat_sessions"))
    assertEquals(0L, count(db, "chat_turns"))
  }

  @Test fun sessionsNewestFirstWithRightTurnCount() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput("Q", "A", emptyList(), ChatAnswerSource.GENERAL)
    store.append(1L, 1000L, ans)
    store.append(2L, 2000L, ans)
    store.append(2L, 3500L, ans)
    store.append(3L, 2500L, ans)

    val sessions = store.sessions()
    assertEquals(listOf(2L, 3L, 1L), sessions.map { it.id })
    assertEquals(listOf(2, 1, 1), sessions.map { it.turns })
    assertEquals(listOf(3500L, 2500L, 1000L), sessions.map { it.lastAtMs })
  }

  @Test fun deleteRemovesTurnsAndCitations() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput(
      "Q",
      "A",
      listOf(ChatCitation("L", null, "T", 100L, MemoryRef(MemoryLevel.STANDING, "g", "m"))),
      ChatAnswerSource.GENERAL,
    )
    store.append(1L, 1000L, ans)
    assertEquals(1L, count(db, "chat_sessions"))
    assertEquals(1L, count(db, "chat_turns"))
    assertEquals(1L, count(db, "chat_citations"))

    store.delete(1L)
    assertEquals(0L, count(db, "chat_sessions"))
    assertEquals(0L, count(db, "chat_turns"))
    assertEquals(0L, count(db, "chat_citations"))
  }

  @Test fun purgeRemovesUnpinnedPastRetentionKeepsInsideAndKeepsPinned() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput(
      "Q",
      "A",
      listOf(ChatCitation("L", null, "T", 100L, MemoryRef(MemoryLevel.STANDING, "g", "m"))),
      ChatAnswerSource.GENERAL,
    )
    val now = 100 * ChatHistoryStore.RETENTION_MS

    // Chat 1: unpinned, just past 7 days (lastAtMs < cutoff)
    val past7Days = now - ChatHistoryStore.RETENTION_MS - 1L
    store.append(1L, past7Days, ans)

    // Chat 2: unpinned, just inside 7 days (lastAtMs >= cutoff)
    val inside7Days = now - ChatHistoryStore.RETENTION_MS + 1000L
    store.append(2L, inside7Days, ans)

    // Chat 3: pinned, old (past 7 days)
    val oldPinned = now - ChatHistoryStore.RETENTION_MS - 5000L
    store.append(3L, oldPinned, ans)
    store.setPinned(3L, true)

    val purged = store.purge(now)
    assertEquals(1, purged)

    val remainingIds = store.sessions().map { it.id }.toSet()
    assertFalse(remainingIds.contains(1L))
    assertTrue(remainingIds.contains(2L))
    assertTrue(remainingIds.contains(3L))

    // Verify Chat 1's turns and citations are removed
    assertEquals(2L, count(db, "chat_sessions"))
    assertEquals(2L, count(db, "chat_turns"))
    assertEquals(2L, count(db, "chat_citations"))
  }

  @Test fun appendPurgesOldChats() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput("Q", "A", emptyList(), ChatAnswerSource.GENERAL)
    val oldMs = 1000L
    store.append(1L, oldMs, ans)
    assertEquals(1, store.sessions().size)

    val newMs = oldMs + ChatHistoryStore.RETENTION_MS + 1000L
    store.append(2L, newMs, ans)

    val remaining = store.sessions().map { it.id }
    assertEquals(listOf(2L), remaining)
  }

  @Test fun setPinnedFalseLetsPurgeTakeIt() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput("Q", "A", emptyList(), ChatAnswerSource.GENERAL)
    val now = 100 * ChatHistoryStore.RETENTION_MS
    val oldMs = now - ChatHistoryStore.RETENTION_MS - 1L
    store.append(1L, oldMs, ans)
    store.setPinned(1L, true)

    assertEquals(0, store.purge(now))
    assertEquals(1, store.sessions().size)

    store.setPinned(1L, false)
    assertEquals(1, store.purge(now))
    assertTrue(store.sessions().isEmpty())
  }

  @Test fun clearEmptiesAllThreeTables() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput(
      "Q",
      "A",
      listOf(ChatCitation("L", null, "T", 100L, MemoryRef(MemoryLevel.STANDING, "g", "m"))),
      ChatAnswerSource.GENERAL,
    )
    store.append(1L, 1000L, ans)
    store.append(2L, 2000L, ans)

    store.clear()
    assertEquals(0L, count(db, "chat_sessions"))
    assertEquals(0L, count(db, "chat_turns"))
    assertEquals(0L, count(db, "chat_citations"))
    assertTrue(store.sessions().isEmpty())
  }

  @Test fun toSessionGivesSameStateAsCallingAfterOneByOneWithGeneralExchangesInGeneral() {
    val ans1 = ChatAnswerOutput("Q1", "A1", emptyList(), ChatAnswerSource.GENERAL)
    val ans2 = ChatAnswerOutput("Q2", "A2", emptyList(), ChatAnswerSource.MODEL)
    val ans3 = ChatAnswerOutput("Q3", "A3", emptyList(), ChatAnswerSource.GENERAL)
    val answers = listOf(ans1, ans2, ans3)

    val manualSession = ChatSessionState(42L).after(ans1).after(ans2).after(ans3)
    val session = answers.toSession(42L)

    assertEquals(manualSession, session)
    assertEquals(2, session.turns)
    assertEquals(listOf(ChatExchange("Q1", "A1"), ChatExchange("Q3", "A3")), session.general)
  }

  @Test fun sqlCharactersInQuestionAreStoredAsText() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val sqlInjection = "'; DROP TABLE chat_turns; --"
    val ans = ChatAnswerOutput(sqlInjection, "ปลอดภัยดีครับ", emptyList(), ChatAnswerSource.GENERAL)
    store.append(1L, 1000L, ans)

    val restored = store.answers(1L).single()
    assertEquals(sqlInjection, restored.question)
    assertEquals("ปลอดภัยดีครับ", restored.text)
    assertEquals(1L, count(db, "chat_turns"))
  }

  @Test fun unknownSourceOrMemoryLevelRowIsSkipped() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val ans = ChatAnswerOutput(
      "Q",
      "A",
      listOf(ChatCitation("L", null, "T", 100L, MemoryRef(MemoryLevel.CORE, "g", "m"))),
      ChatAnswerSource.GENERAL,
    )
    store.append(1L, 1000L, ans)

    // Corrupt the citation's level in DB
    db.execSQL("UPDATE chat_citations SET level = 'UNKNOWN_LEVEL'")
    val answerWithSkippedCitation = store.answers(1L).single()
    assertTrue(answerWithSkippedCitation.citations.isEmpty())

    // Corrupt turn source in DB
    db.execSQL("UPDATE chat_turns SET source = 'UNKNOWN_SOURCE'")
    assertTrue(store.answers(1L).isEmpty())
  }

  @Test fun titleIsTruncatedToTitleChars() {
    val db = openDb()
    val store = ChatHistoryStore(db)
    val longQuestion = "a".repeat(100)
    store.append(1L, 1000L, ChatAnswerOutput(longQuestion, "A", emptyList(), ChatAnswerSource.GENERAL))
    val session = store.sessions().single()
    assertEquals("a".repeat(ChatHistoryStore.TITLE_CHARS), session.title)
    assertEquals(ChatHistoryStore.TITLE_CHARS, session.title.length)
  }
}
