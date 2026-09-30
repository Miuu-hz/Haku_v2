package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.language.TokenEstimator
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private class MemSummaries : EveningSummaryRepository {
  val rows = mutableMapOf<String, EveningSummaryRecord>()
  var saves = 0
  override fun find(day: String) = rows[day]
  override fun save(record: EveningSummaryRecord) { rows[record.day] = record; saves++ }
  override fun delete(day: String) = rows.remove(day) != null
  override fun clear() = rows.clear()
}

private class ScriptedModel(private val replies: MutableList<String>) : LocalModel {
  var prompts = mutableListOf<String>()
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow {
    prompts += input.prompt
    emit(replies.removeAt(0))
  }
  override fun cancel() {}
  override suspend fun unload() {}
}

private class BrokenModel : LocalModel {
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow { error("native crash") }
  override fun cancel() {}
  override suspend fun unload() {}
}

class EveningSummarizerTest {
  private val day = "2026-09-07"
  private val krap = DiaryStyle(EndingParticle.KRAP)
  private val lines = listOf(
    "08:30 ออกจากบ้านครับ",
    "09:10 ถึงที่ทำงาน ใช้เวลาเดินทางราว 40 นาที อยู่ราว 8 ชั่วโมงครับ",
    "18:00 มีนัด “ดินเนอร์กับแม่”ครับ",
  )
  private val good = "เช้านี้ออกจากบ้านตอน 08:30 แล้วถึงที่ทำงานตอน 09:10 ใช้เวลาเดินทางราว 40 นาที เย็นมีนัด “ดินเนอร์กับแม่” ตอน 18:00 ครับ"
  private val repo = MemSummaries()

  private fun summarizer(model: LocalModel, budget: Int = SummaryPromptBuilder.DEFAULT_BUDGET_TOKENS) =
    EveningSummarizer(LocalModelClient(model), repo, budget)

  private fun input(regen: Boolean = false, style: DiaryStyle = krap, l: List<String> = lines) =
    EveningSummaryInput(day, l, style, regenerate = regen)

  @Test fun `good model reply is used and stored with inferred provenance`() = runTest {
    val out = summarizer(ScriptedModel(mutableListOf(good))).summarize(input())
    assertEquals(SummarySource.MODEL, out.source)
    assertEquals(good, out.text)
    assertTrue(out.rejectedReasons.isEmpty())
    assertNotNull(out.metrics)
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, repo.rows.getValue(day).provenance)
  }

  @Test fun `invented time and place fall back to the template and say why`() = runTest {
    val bad = "เช้านี้ออกจากบ้านตอน 07:45 แล้วไปถึงร้านกาแฟตอน 09:10 ครับ"
    val out = summarizer(ScriptedModel(mutableListOf(bad))).summarize(input())
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertTrue("invented-time" in out.rejectedReasons)
    assertTrue("invented-place" in out.rejectedReasons)
    assertTrue(out.text.contains("08:30 ออกจากบ้าน"))
    assertFalse(out.text.contains("07:45"))
    assertNull(repo.find(day)) // template is not frozen into the store
  }

  @Test fun `wrong particle is rejected`() = runTest {
    val out = summarizer(ScriptedModel(mutableListOf(good.removeSuffix("ครับ") + "ค่ะ"))).summarize(input())
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertTrue("wrong-particle" in out.rejectedReasons)
  }

  @Test fun `template ends with the chosen particle and no data day is friendly and skips the model`() = runTest {
    val model = ScriptedModel(mutableListOf())
    val out = summarizer(model).summarize(input(l = emptyList(), style = DiaryStyle(EndingParticle.KA)))
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertEquals(SummaryTemplate.NO_DATA + "ค่ะ", out.text)
    assertTrue(model.prompts.isEmpty())
    assertNull(out.metrics)
    assertNull(repo.find(day))
    assertTrue(SummaryTemplate.paragraph(day, lines, krap).endsWith("ครับ"))
  }

  @Test fun `second call returns the stored text and only regenerate asks the model again`() = runTest {
    val model = ScriptedModel(mutableListOf(good, good.replace("เช้านี้", "ตอนเช้า")))
    val s = summarizer(model)
    val first = s.summarize(input())
    val again = s.summarize(input())
    assertTrue(again.fromStore)
    assertEquals(first.text, again.text)
    assertEquals(1, model.prompts.size)
    val regenerated = s.summarize(input(regen = true))
    assertEquals(2, model.prompts.size)
    assertTrue(regenerated.text.startsWith("ตอนเช้า"))
    assertEquals(regenerated.text, repo.find(day)?.text)
    assertEquals(2, repo.saves)
  }

  @Test fun `model failure falls back to the template`() = runTest {
    val out = summarizer(BrokenModel()).summarize(input())
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertEquals(listOf("model-error: IllegalStateException"), out.rejectedReasons)
    assertTrue(out.text.isNotBlank())
  }

  @Test fun `prompt stays inside the token budget and only fitting lines count as facts`() = runTest {
    val many = (1..40).map { "%02d:00 ถึงที่ทำงานครับ".format(it % 24) }
    val budget = 400
    val model = ScriptedModel(mutableListOf(good))
    summarizer(model, budget).summarize(input(l = many))
    assertTrue(TokenEstimator.estimate(model.prompts.single()) <= budget)
    val p = SummaryPromptBuilder.build(SummaryPromptInput("h", many, krap, listOf("บริบท")), budget)
    assertTrue(p.estimatedTokens <= budget)
    assertTrue(p.factLines.size < many.size && p.factLines.isNotEmpty())
  }

  @Test fun `store round trips and rejects a bad day`() {
    val store = EveningSummaryStore(FakeFileSystem(), "/sum".toPath())
    assertNull(store.find(day))
    val rec = EveningSummaryRecord(day, "บรรทัดแรก\n\nบรรทัดสาม ครับ", SummarySource.MODEL)
    store.save(rec)
    assertEquals(rec, store.find(day))
    assertFailsWithBadDay { store.find("../x") }
  }

  private fun assertFailsWithBadDay(block: () -> Unit) {
    val ok = runCatching(block).exceptionOrNull() is IllegalArgumentException
    assertTrue(ok)
  }

  @Test fun `a reply that repeats the prompt header date passes the check`() = runTest {
    val reply = "วันจันทร์ที่ 7 กันยายน พ.ศ. 2569 ออกจากบ้านตอน 08:30 แล้วถึงที่ทำงานตอน 09:10 ใช้เวลาเดินทางราว 40 นาทีครับ"
    val out = summarizer(ScriptedModel(mutableListOf(reply))).summarize(input())
    assertEquals(SummarySource.MODEL, out.source, out.rejectedReasons.toString())
  }

  @Test fun `a timeout does not poison the next model run`() = runTest {
    val s = EveningSummarizer(LocalModelClient(SlowThenFastModel(good)), repo, timeoutMillis = 1_000)
    val first = s.summarize(input())
    assertEquals(listOf("timeout"), first.rejectedReasons)
    val second = s.summarize(input())
    assertEquals(SummarySource.MODEL, second.source)
  }
}

private class SlowThenFastModel(private val reply: String) : LocalModel {
  private var calls = 0
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow {
    if (calls++ == 0) delay(10_000)
    emit(reply)
  }
  override fun cancel() {}
  override suspend fun unload() {}
}
