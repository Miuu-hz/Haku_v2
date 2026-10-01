package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.context.RecallRig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

private class ReplyModel(private val reply: String) : LocalModel {
  val prompts = mutableListOf<String>()
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow { prompts += input.prompt; emit(reply) }
  override fun cancel() {}
  override suspend fun unload() {}
}

class ChatPresenterTest {
  private val rig = RecallRig()
  private val chat = ChatPresenter(rig.memory, rig.index, rig.pipeline, RecallRig.OFFSET_MIN)
  private val coffee = "ครั้งล่าสุดที่ไปร้านกาแฟคือเมื่อไร"

  @AfterTest fun close() = rig.close()

  private fun seed() {
    val p = Provenance.INFERRED_FROM_USER_DATA
    rig.daily("08:00 ออกจากบ้าน", "2026-09-29T01:00:00.000Z", p)
    rig.daily("12:00 ไปกินข้าวที่ร้านกาแฟ", "2026-09-29T05:00:00.000Z", p)
    rig.daily("11:00 ไปร้านกาแฟ", "2026-09-20T04:00:00.000Z", p)
  }

  private fun ask(q: String, style: DiaryStyle = DiaryStyle()) = chat.retrieve(ChatQuestionInput(q, RecallRig.NOW, style))

  @Test fun `without a model the answer is the retrieved lines with date citations`() = runTest {
    seed()
    val out = chat.answer(ask(coffee, DiaryStyle(EndingParticle.KA)), client = null)
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals("จากบันทึกของคุณ พบ 2 รายการที่เกี่ยวข้อง ล่าสุดคือวันอังคารที่ 29 กันยายน พ.ศ. 2569 เวลา 12:00 ค่ะ", out.text)
    assertEquals(listOf("อ 29 ก.ย.", "อา 20 ก.ย."), out.citations.map { it.label })
    assertEquals(listOf("2026-09-29", "2026-09-20"), out.citations.map { it.day })
    assertEquals("12:00 ไปกินข้าวที่ร้านกาแฟ", out.citations.first().text) // the stored line, not the prompt label
  }

  @Test fun `date question cites that day in time order`() = runTest {
    seed()
    val out = chat.answer(ask("เมื่อวานไปไหนมา"), null)
    assertEquals(listOf("08:00 ออกจากบ้าน", "12:00 ไปกินข้าวที่ร้านกาแฟ"), out.citations.map { it.text })
  }

  @Test fun `nothing found says so and never asks the model`() = runTest {
    val model = ReplyModel("ไปหาหมอฟันเมื่อวันจันทร์ที่ 14 กันยายน ตอน 10:00 นะ")
    val out = chat.answer(ask("ไปหาหมอฟันครั้งล่าสุดเมื่อไร", DiaryStyle(EndingParticle.KRAP)), LocalModelClient(model))
    assertTrue(out.citations.isEmpty())
    assertEquals(EndingParticle.KRAP.appendTo(ChatPresenter.NOT_FOUND), out.text)
    assertTrue(model.prompts.isEmpty())
  }

  @Test fun `checked model answer is used and keeps the citations`() = runTest {
    seed()
    val model = ReplyModel("ครั้งล่าสุดที่ไปร้านกาแฟคือวันอังคารที่ 29 กันยายน ตอน 12:00")
    val out = chat.answer(ask(coffee), LocalModelClient(model))
    assertEquals(ChatAnswerSource.MODEL, out.source, out.rejectedReasons.toString())
    assertEquals(2, out.citations.size)
    assertTrue("[2026-09-29 12:00]" in model.prompts.single())
  }

  @Test fun `model answer with an invented time falls back to the retrieval answer`() = runTest {
    seed()
    val out = chat.answer(ask(coffee), LocalModelClient(ReplyModel("ครั้งล่าสุดที่ไปร้านกาแฟคือวันอังคารที่ 29 กันยายน ตอน 15:45")))
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertTrue("invented-time" in out.rejectedReasons)
    assertTrue(out.text.startsWith(ChatPresenter.FROM_DIARY))
  }
}
