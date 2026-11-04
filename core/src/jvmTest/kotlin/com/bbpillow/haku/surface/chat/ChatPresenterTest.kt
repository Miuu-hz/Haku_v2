package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.context.ContextAssembler
import com.bbpillow.haku.thinking.context.JdkWordSegmenter
import com.bbpillow.haku.thinking.context.NowStateInput
import com.bbpillow.haku.thinking.context.RecallRig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** Questions in scripts/stress/questions_th.txt that are not about the user's life, so general is the right path. */
private val STRESS_SET_ALLOWED_GENERAL = setOf("คุณชื่ออะไร", "ช่วยแนะนำตัวหน่อย")

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

  @Test fun `general knowledge gets the model's own long answer with no records and the general source`() = runTest {
    seed()
    val reply = "ต้มน้ำ 3 ถ้วย ใส่ข่า 5 แว่น ตะไคร้ 2 ต้น แล้วเติมกุ้ง 12 ตัว ".repeat(20).trim()
    val model = ReplyModel(reply)
    val out = chat.answer(ask("สูตรต้มยำกุ้งทำยังไง"), LocalModelClient(model))
    assertEquals(ChatAnswerSource.GENERAL, out.source)
    assertEquals(reply, out.text) // longer than MAX_CHAT_CHARS and full of numbers: neither cut nor refused
    assertTrue(out.text.length > ChatPresenter.MAX_CHAT_CHARS)
    assertTrue(out.citations.isEmpty())
    assertFalse("ร้านกาแฟ" in model.prompts.single(), "general path must not see the diary")
  }

  @Test fun `a question about right now stays on the diary path`() {
    val withNow = ChatPresenter(rig.memory, rig.index, rig.pipeline, RecallRig.OFFSET_MIN, nowState = { NowStateInput(placeName = "ร้านกาแฟ") })
    assertEquals(ChatRoute.DIARY, withNow.retrieve(ChatQuestionInput("ตอนนี้อยู่ไหน", RecallRig.NOW)).route)
  }

  @Test fun `a knowledge question that shares a word with the diary stays general`() = runTest {
    seed()
    assertEquals(ChatRoute.GENERAL, ask("กาแฟกับชาต่างกันยังไง").route)
  }

  @Test fun `a question about the user's own life stays on the checked diary path`() = runTest {
    seed()
    val out = chat.answer(ask("ฉันไปร้านกาแฟกี่ครั้ง"), LocalModelClient(ReplyModel("ไปสนามบินเวลา 09:45")))
    assertEquals(ChatRoute.DIARY, ask("ฉันไปร้านกาแฟกี่ครั้ง").route)
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source) // invented place and time refused, as before
    assertTrue(out.citations.isNotEmpty())
  }

  @Test fun `general question without a model says so instead of not found in the diary`() = runTest {
    val out = chat.answer(ask("คอมพิวเตอร์ควอนตัมคืออะไร", DiaryStyle(EndingParticle.KA)), client = null)
    assertEquals(EndingParticle.KA.appendTo(ChatPresenter.GENERAL_UNAVAILABLE), out.text)
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
  }

  @Test fun `general question with blank model output falls back to the unavailable line`() = runTest {
    val out = chat.answer(ask("คอมพิวเตอร์ควอนตัมคืออะไร"), LocalModelClient(ReplyModel("  \n ")))
    assertEquals(ChatPresenter.GENERAL_UNAVAILABLE, out.text)
    assertEquals(listOf("blank"), out.rejectedReasons)
  }

  // P3.8 pass criterion: these use the real TimeWords and search, which a router unit test does not
  @Test fun `general set routes general through real retrieval`() {
    seed()
    val general = listOf(
      "สูตรต้มยำกุ้งทำยังไง", "วิธีหุงข้าวด้วยหม้อหุงข้าว", "15 คูณ 12 เท่ากับเท่าไหร่", "2+2 ได้เท่าไร", "อธิบายแรงโน้มถ่วงให้เข้าใจง่าย",
      "คอมพิวเตอร์ควอนตัมคืออะไร", "แปลคำว่า good morning เป็นภาษาไทย", "hello แปลว่าอะไร", "ช่วยเขียนอีเมลลาป่วยให้หน่อย",
      "เล่านิทานสั้น ๆ ให้ฟังหน่อย", "ทำไมท้องฟ้าถึงเป็นสีฟ้า", "ประเทศไทยมีกี่จังหวัด", "เมืองหลวงของญี่ปุ่นคือที่ไหน",
      "แนะนำหนังสือสำหรับคนเริ่มอ่านหน่อย", "ผมร่วงแก้ยังไง", "เราเตอร์คืออะไร", "ข้อดีของการวิ่งมีอะไรบ้าง", "กาแฟกับชาต่างกันยังไง",
      "เขียนโค้ด python บวกเลขให้หน่อย", "ปวดหัวควรทำยังไง", "ดวงอาทิตย์คืออะไร", "ปีแสงคืออะไร",
    )
    assertEquals(emptyList(), general.filter { ask(it).route != ChatRoute.GENERAL })
  }

  @Test fun `the stress set about the user's life stays on the diary path`() = RecallRig(JdkWordSegmenter).use { real ->
    // a real Thai word breaker: a dropped subject reaches the diary only through its word hit
    val realChat = ChatPresenter(real.memory, real.index, real.pipeline, RecallRig.OFFSET_MIN)
    val p = Provenance.INFERRED_FROM_USER_DATA
    real.daily("12:00 ไปกินข้าวที่ร้านกาแฟ", "2026-09-29T05:00:00.000Z", p)
    real.daily("09:00 ส่งใบเสนอราคาใหม่ให้ลูกค้า", "2026-09-28T02:00:00.000Z", p)
    real.daily("10:00 จองตั๋วเครื่องบินไปเชียงใหม่", "2026-09-28T03:00:00.000Z", p)
    real.daily("07:00 วิ่งที่สวนลุมพินี 3 รอบ", "2026-09-27T00:00:00.000Z", p)
    real.daily("18:00 ประชุมทีมขาย", "2026-09-27T11:00:00.000Z", p)
    real.daily("22:00 ตั้งใจเข้านอนก่อนห้าทุ่ม", "2026-09-27T15:00:00.000Z", p)
    real.daily("11:00 เบอร์โทรศัพท์หมอฟัน 02-000-0000", "2026-09-26T04:00:00.000Z", p)
    val questions = java.io.File("../scripts/stress/questions_th.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val general = questions.filter { realChat.retrieve(ChatQuestionInput(it, RecallRig.NOW, DiaryStyle())).route == ChatRoute.GENERAL }
    assertEquals(emptyList(), general - STRESS_SET_ALLOWED_GENERAL)
  }

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
    val out = chat.answer(ask("ไปหาหมอฟันครั้งล่าสุดเมื่อไร", DiaryStyle(EndingParticle.KRAP)), LocalModelClient(ReplyModel("   \n  ")))
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

  @Test fun `echo of statement is rejected and falls back to acknowledgement`() = runTest {
    val q = "ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก"
    val echo = "มิ้นท์ ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก ค่ะ"
    val out = chat.answer(ask(q, DiaryStyle(EndingParticle.KA, nickname = "มิ้นท์")), LocalModelClient(ReplyModel(echo)))
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals(listOf("echo"), out.rejectedReasons)
    assertEquals(echo, out.rejectedText)
    assertEquals(EndingParticle.KA.appendTo(ChatPresenter.ACKNOWLEDGEMENT), out.text)
  }

  @Test fun `natural caring reply to a statement without echo is accepted`() = runTest {
    val q = "ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก"
    val reply = "สู้ๆ นะคะ พักผ่อนให้เพียงพอค่ะ"
    val out = chat.answer(ask(q, DiaryStyle(EndingParticle.KA, nickname = "มิ้นท์")), LocalModelClient(ReplyModel(reply)))
    assertEquals(ChatAnswerSource.MODEL, out.source, out.rejectedReasons.toString())
    assertEquals(reply, out.text)
    assertTrue(out.rejectedReasons.isEmpty())
  }

  @Test fun isEchoDetectsVerbatimRepetitionWithParticleOrNickname() {
    val q = "ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก"
    assertTrue(ChatPresenter.isEcho(q, "มิ้นท์ ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก ค่ะ"))
    assertTrue(ChatPresenter.isEcho(q, "ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก ค่ะ"))
    assertTrue(ChatPresenter.isEcho(q, "ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก"))
    assertFalse(ChatPresenter.isEcho(q, "สู้ๆ นะคะ พักผ่อนให้เพียงพอค่ะ"))
    assertFalse(ChatPresenter.isEcho("ครั้งล่าสุดที่ไปร้านกาแฟคือเมื่อไร", "ครั้งล่าสุดที่ไปร้านกาแฟคือวันอังคารที่ 29 กันยายน ตอน 12:00 ค่ะ"))
  }

  @Test fun `past-tense ไปไหนมาบ้าง with nowState does not cite now and uses recent fallback newest first`() = runTest {
    seed()
    val withNow = ChatPresenter(rig.memory, rig.index, rig.pipeline, RecallRig.OFFSET_MIN, nowState = { NowStateInput(placeName = "สถานที่หนึ่ง") })
    val r = withNow.retrieve(ChatQuestionInput("ไปไหนมาบ้าง", RecallRig.NOW))
    assertTrue(r.citations.none { it.label == ContextAssembler.NOW_LABEL })
    assertEquals(listOf("12:00 ไปกินข้าวที่ร้านกาแฟ", "08:00 ออกจากบ้าน"), r.citations.map { it.text })
  }

  @Test fun `isQuestion classifies statements and questions accurately`() {
    assertFalse(ChatPresenter.isQuestion("เหนื่อยมาก"))
    assertTrue(ChatPresenter.isQuestion("เหนื่อยไหม"))
    assertTrue(ChatPresenter.isQuestion("ช่วยสรุปวันนี้"))
    assertFalse(ChatPresenter.isQuestion("วันนี้ได้โบนัส!"))
    assertFalse(ChatPresenter.isQuestion("ขอบคุณมาก"))
    assertFalse(ChatPresenter.isQuestion("ปวดหัวมีปัญหานิดหน่อย"))
    assertTrue(ChatPresenter.isQuestion("วันนี้เป็นไง"))
    assertTrue(ChatPresenter.isQuestion("กินข้าวหรือยัง"))
    assertFalse(ChatPresenter.isQuestion("ทำงานทั้งวันพรุ่งนี้ เหนื่อยมาก"))
    assertTrue(ChatPresenter.isQuestion("ครั้งล่าสุดที่ไปร้านกาแฟคือเมื่อไร"))
    assertTrue(ChatPresenter.isQuestion("ตอนนี้อยู่ไหน"))
    assertTrue(ChatPresenter.isQuestion("สบายดีไหม?"))
  }

  @Test fun `statement with many matching records uses statement prompt and rejected model text falls back to acknowledgement without citations`() = runTest {
    val p = Provenance.INFERRED_FROM_USER_DATA
    for (i in 1..5) rig.daily("09:00 ทำงานวันที่ $i", "2026-09-2${i}T02:00:00.000Z", p)
    val q = "ทำงานหนักมาก เหนื่อยสุดๆ" // no time word: "พรุ่งนี้" would scope retrieval to tomorrow (no records)
    val r = ask(q, DiaryStyle(EndingParticle.KA))
    assertEquals(ChatAnswerSource.RETRIEVAL, r.fallback.source)
    assertEquals(EndingParticle.KA.appendTo(ChatPresenter.ACKNOWLEDGEMENT), r.fallback.text)
    assertTrue(r.fallback.citations.isEmpty())

    val rejectedModel = ReplyModel("[areas] ค่ะ")
    val out = chat.answer(r, LocalModelClient(rejectedModel))

    // 1. statement prompt chosen (contains statement instructions, does not contain fact dump)
    val prompt = rejectedModel.prompts.single()
    assertTrue("ผู้ใช้บอกเล่า พูดคุย หรือระบายความรู้สึก" in prompt)
    assertFalse("ข้อเท็จจริง:" in prompt)

    // 2. rejected model text falls back to "รับทราบแล้ว" + particle, no citations
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals(EndingParticle.KA.appendTo(ChatPresenter.ACKNOWLEDGEMENT), out.text)
    assertTrue(out.citations.isEmpty())
    assertTrue(out.rejectedReasons.isNotEmpty())
    assertEquals("[areas] ค่ะ", out.rejectedText)
  }

  @Test fun `statement with many matching records and accepted model text has no citations`() = runTest {
    val p = Provenance.INFERRED_FROM_USER_DATA
    for (i in 1..5) rig.daily("09:00 ทำงานวันที่ $i", "2026-09-2${i}T02:00:00.000Z", p)
    val q = "ทำงานหนักมาก เหนื่อยสุดๆ" // no time word: "พรุ่งนี้" would scope retrieval to tomorrow (no records)
    val r = ask(q, DiaryStyle(EndingParticle.KA))
    val model = ReplyModel("สู้ๆ นะคะ พักผ่อนให้เพียงพอค่ะ")
    val out = chat.answer(r, LocalModelClient(model))
    assertEquals(ChatAnswerSource.MODEL, out.source)
    assertEquals("สู้ๆ นะคะ พักผ่อนให้เพียงพอค่ะ", out.text)
    assertTrue(out.citations.isEmpty())
  }

  @Test fun `question with matches keeps prompt and citations unchanged`() = runTest {
    seed()
    val model = ReplyModel("ครั้งล่าสุดที่ไปร้านกาแฟคือวันอังคารที่ 29 กันยายน ตอน 12:00")
    val out = chat.answer(ask(coffee), LocalModelClient(model))
    assertEquals(ChatAnswerSource.MODEL, out.source)
    assertEquals(2, out.citations.size)
    val prompt = model.prompts.single()
    assertTrue("ข้อเท็จจริง:" in prompt)
    assertTrue("[2026-09-29 12:00]" in prompt)
  }

  @Test fun `sanitizePromptFacts removes raw section tags while preserving date stamps and now label`() {
    val raw = listOf(
      "- [areas] ทำงานที่ออฟฟิศ",
      "- [you] แพ้ถั่ว",
      "- [2026-09-29 12:00] ไปกินข้าวที่ร้านกาแฟ",
      "- [ตอนนี้] อยู่ที่บ้าน",
      "- ตอบสั้นๆ ภาษาไทยเสมอ",
    ).joinToString("\n")
    val sanitized = ChatPresenter.sanitizePromptFacts(raw)
    assertEquals(
      listOf(
        "- ทำงานที่ออฟฟิศ",
        "- แพ้ถั่ว",
        "- [2026-09-29 12:00] ไปกินข้าวที่ร้านกาแฟ",
        "- [ตอนนี้] อยู่ที่บ้าน",
        "- ตอบสั้นๆ ภาษาไทยเสมอ",
      ).joinToString("\n"),
      sanitized,
    )
  }

  @Test fun `statement with blank model output falls back to acknowledgement`() = runTest {
    val out = chat.answer(ask("ทำงานทั้งวัน เหนื่อยมาก", DiaryStyle(EndingParticle.KRAP)), LocalModelClient(ReplyModel("   \n  ")))
    assertTrue(out.citations.isEmpty())
    assertEquals(ChatAnswerSource.RETRIEVAL, out.source)
    assertEquals(EndingParticle.KRAP.appendTo(ChatPresenter.ACKNOWLEDGEMENT), out.text)
  }
}
