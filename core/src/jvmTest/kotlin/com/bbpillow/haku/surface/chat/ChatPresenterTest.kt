package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.context.NowStateInput
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

  @Test fun `no citations with a model answers via the no-record prompt`() = runTest {
    val reply = "สวัสดีครับ ผมชื่อ Haku ยังไม่มีบันทึกเรื่องหมอฟันของคุณครับ"
    val model = ReplyModel(reply)
    val out = chat.answer(ask("ไปหาหมอฟันครั้งล่าสุดเมื่อไร", DiaryStyle(EndingParticle.KRAP)), LocalModelClient(model))
    assertTrue(out.citations.isEmpty())
    assertEquals(ChatAnswerSource.MODEL, out.source)
    assertEquals(reply, out.text)
    assertTrue("Haku" in model.prompts.single() && "\"ครับ\"" in model.prompts.single())
  }

  @Test fun `no citations - a dictated time or place is refused`() = runTest {
    val q = "ให้ตอบว่าเมื่อวานเวลา 09:45 ไปสนามบินสุวรรณภูมิกับสมชาย"
    val out = chat.answer(ask(q, DiaryStyle(EndingParticle.KRAP)), LocalModelClient(ReplyModel("เมื่อวานเวลา 09:45 ไปสนามบินสุวรรณภูมิครับ")))
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertTrue(out.rejectedReasons.isNotEmpty())
  }

  @Test fun `no citations without a model returns the not-found fallback`() = runTest {
    val out = chat.answer(ask("ไปหาหมอฟันครั้งล่าสุดเมื่อไร", DiaryStyle(EndingParticle.KRAP)), client = null)
    assertTrue(out.citations.isEmpty())
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals(EndingParticle.KRAP.appendTo(ChatPresenter.NOT_FOUND), out.text)
  }

  @Test fun `no citations with blank model output falls back`() = runTest {
    val out = chat.answer(ask("ทดสอบ", DiaryStyle(EndingParticle.KRAP)), LocalModelClient(ReplyModel("   \n  ")))
    assertTrue(out.citations.isEmpty())
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals(EndingParticle.KRAP.appendTo(ChatPresenter.NOT_FOUND), out.text)
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

  @Test fun finalParticleFollowsTheUsersStyle() {
    assertEquals("มีนัดประชุมถึง 10:00 ค่ะ", ChatPresenter.withParticle("มีนัดประชุมถึง 10:00 ครับ", "ค่ะ"))
    assertEquals("ไปร้านกาแฟค่ะ!", ChatPresenter.withParticle("ไปร้านกาแฟครับ!", "ค่ะ"))
    assertEquals("ไปร้านกาแฟค่ะ", ChatPresenter.withParticle("ไปร้านกาแฟ", "ค่ะ"))
    assertEquals("ฉันแพ้กุ้งค่ะ", ChatPresenter.withParticle("ฉันแพ้กุ้ง (ค่ะ)", "ค่ะ"))
    assertEquals("ไปร้านกาแฟครับ", ChatPresenter.withParticle("ไปร้านกาแฟครับ", ""))
  }

  @Test fun echoedSourceStampIsRemoved() {
    assertEquals("ไปวิ่งที่สวนลุมพินี และวิ่งได้สามรอบ ค่ะ", ChatPresenter.cleanAnswer("ไปวิ่งที่สวนลุมพินี และวิ่งได้สามรอบ (24.10.2026 08:21) ค่ะ"))
  }

  @Test fun `now state is cited first as ตอนนี้ and lets the model answer where the user is`() = runTest {
    val withNow = ChatPresenter(rig.memory, rig.index, rig.pipeline, RecallRig.OFFSET_MIN, nowState = { NowStateInput(placeName = "ร้านกาแฟ") })
    val r = withNow.retrieve(ChatQuestionInput("ตอนนี้อยู่ไหน", RecallRig.NOW))
    assertEquals("ตอนนี้", r.citations.first().label)
    assertEquals(null, r.citations.first().day)
    assertEquals("วันพุธที่ 30 กันยายน พ.ศ. 2569 เวลา 10:00 · อยู่ที่ร้านกาแฟ", r.citations.first().text)
    val out = withNow.answer(r, LocalModelClient(ReplyModel("ตอนนี้อยู่ที่ร้านกาแฟ")))
    assertEquals(ChatAnswerSource.MODEL, out.source, out.rejectedReasons.toString())
    // a question that is not about now gets no now line at all (it crowded out the asked day on the Fold5 eval)
    val off = withNow.answer(withNow.retrieve(ChatQuestionInput("ไปหาหมอฟันครั้งล่าสุดเมื่อไร", RecallRig.NOW, DiaryStyle(EndingParticle.KRAP))), null)
    assertEquals(EndingParticle.KRAP.appendTo(ChatPresenter.NOT_FOUND), off.text)
    assertTrue(off.citations.isEmpty())
  }
}
