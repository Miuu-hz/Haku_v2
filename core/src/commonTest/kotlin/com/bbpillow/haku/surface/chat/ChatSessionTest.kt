package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.thinking.ChatExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChatSessionTest {
  @Test
  fun `a chat goes stale after 30 quiet minutes, and a new one never does`() {
    val chat = ChatSessionState(id = 1L, lastAtMs = 1_000L)
    assertEquals(false, chat.isStale(1_000L + ChatSessionState.IDLE_NEW_CHAT_MS))
    assertEquals(true, chat.isStale(1_001L + ChatSessionState.IDLE_NEW_CHAT_MS))
    assertEquals(false, ChatSessionState(id = 1L).isStale(Long.MAX_VALUE))
  }

  @Test
  fun `after a GENERAL answer appends the exchange, bumps turns, sets lastRoute GENERAL`() {
    val initial = ChatSessionState(id = 1L)
    val answer = ChatAnswerOutput(
      question = "สวัสดี",
      text = "สวัสดีครับ",
      citations = emptyList(),
      source = ChatAnswerSource.GENERAL,
    )
    val next = initial.after(answer)
    assertEquals(listOf(ChatExchange("สวัสดี", "สวัสดีครับ")), next.general)
    assertEquals(1, next.turns)
    assertEquals(ChatRoute.GENERAL, next.lastRoute)
  }

  @Test
  fun `after a RETRIEVAL or MODEL answer sets lastRoute DIARY and lastDiaryQuestion, adds no exchange`() {
    val initial = ChatSessionState(id = 1L)
    val retrievalAnswer = ChatAnswerOutput(
      question = "เมื่อวานไปไหนมา",
      text = "ไปร้านกาแฟ",
      citations = emptyList(),
      source = ChatAnswerSource.RETRIEVAL,
    )
    val afterRetrieval = initial.after(retrievalAnswer)
    assertEquals(emptyList(), afterRetrieval.general)
    assertEquals(0, afterRetrieval.turns)
    assertEquals(ChatRoute.DIARY, afterRetrieval.lastRoute)
    assertEquals("เมื่อวานไปไหนมา", afterRetrieval.lastDiaryQuestion)

    val modelAnswer = ChatAnswerOutput(
      question = "วันนี้กินอะไร",
      text = "กินข้าว",
      citations = emptyList(),
      source = ChatAnswerSource.MODEL,
    )
    val afterModel = initial.after(modelAnswer)
    assertEquals(emptyList(), afterModel.general)
    assertEquals(0, afterModel.turns)
    assertEquals(ChatRoute.DIARY, afterModel.lastRoute)
    assertEquals("วันนี้กินอะไร", afterModel.lastDiaryQuestion)
  }

  @Test
  fun `after a follow-up diary answer chains`() {
    val initial = ChatSessionState(id = 1L, lastRoute = ChatRoute.DIARY, lastDiaryQuestion = "เมื่อวานไปไหนมา")
    val answer = ChatAnswerOutput(
      question = "แล้วกับใคร",
      text = "ไปคนเดียว",
      citations = emptyList(),
      source = ChatAnswerSource.RETRIEVAL,
    )
    val next = initial.after(answer)
    assertEquals("เมื่อวานไปไหนมา แล้วกับใคร", next.lastDiaryQuestion)
    assertEquals(ChatRoute.DIARY, next.lastRoute)
  }

  @Test
  fun `after an answer with actionId returns the same state`() {
    val initial = ChatSessionState(id = 1L, turns = 2, lastRoute = ChatRoute.GENERAL)
    val answer = ChatAnswerOutput(
      question = "ทำอันนี้",
      text = "ดำเนินการ",
      citations = emptyList(),
      source = ChatAnswerSource.GENERAL,
      actionId = "x",
    )
    val next = initial.after(answer)
    assertSame(initial, next)
  }

  @Test
  fun `fitted at or under half of maxTokens returns same instance, over half keeps newest within quarter`() {
    val ex = { i: Int -> ChatExchange("u$i", "m$i") } // 2 chars + 2 chars = 4 chars per exchange
    // maxTokens = 40. Half = 20, Quarter = 10.

    // 5 exchanges * 4 chars = 20 chars (exactly half of 40)
    val atHalf = ChatSessionState(id = 1L, general = (1..5).map { ex(it) }, turns = 5)
    assertSame(atHalf, atHalf.fitted(40))

    // 4 exchanges * 4 chars = 16 chars (under half of 40)
    val underHalf = ChatSessionState(id = 1L, general = (1..4).map { ex(it) }, turns = 4)
    assertSame(underHalf, underHalf.fitted(40))

    // 6 exchanges * 4 chars = 24 chars (over half of 40)
    // Quarter = 10: from newest backwards, ex(6) uses 4 <= 10, ex(5) uses 8 <= 10, ex(4) uses 12 > 10 (stops)
    // Keeps newest: ex(5), ex(6)
    val overHalf = ChatSessionState(id = 1L, general = (1..6).map { ex(it) }, turns = 6)
    val fitted = overHalf.fitted(40)
    assertEquals(listOf(ex(5), ex(6)), fitted.general)
    assertEquals(6, fitted.turns)
  }

  @Test
  fun `thread carries id, system, general and turns`() {
    val exchanges = listOf(ChatExchange("คำถาม", "คำตอบ"))
    val state = ChatSessionState(id = 42L, general = exchanges, turns = 7)
    val thread = state.thread("s")
    assertEquals(42L, thread.id)
    assertEquals("s", thread.system)
    assertEquals(exchanges, thread.history)
    assertEquals(7, thread.turnsBefore)
  }
}
