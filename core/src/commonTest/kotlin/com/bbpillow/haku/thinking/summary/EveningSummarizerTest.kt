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

/** One reply per call, in order; an empty reply once the script runs out. */
private class ScriptedModel(private val replies: MutableList<String>) : LocalModel {
  var prompts = mutableListOf<String>()
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow {
    prompts += input.prompt
    emit(replies.removeFirstOrNull() ?: "")
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
  private val morning = "ช่วงเช้าออกจากบ้านตอน 08:30 แล้วถึงที่ทำงานตอน 09:10 ใช้เวลาเดินทางราว 40 นาที"
  private val evening = "ช่วงเย็นมีนัด “ดินเนอร์กับแม่” ตอน 18:00"
  private val good = "$morning $evening ครับ" // a digit last: appendTo puts a space before the particle
  private val repo = MemSummaries()

  private fun summarizer(model: LocalModel, budget: Int = SummaryPromptBuilder.DEFAULT_BUDGET_TOKENS) =
    EveningSummarizer(LocalModelClient(model), repo, budget)

  private fun input(regen: Boolean = false, style: DiaryStyle = krap, l: List<String> = lines) =
    EveningSummaryInput(day, l, style, regenerate = regen)

  @Test fun `each non-empty part gets one call and the joined text is stored with inferred provenance`() = runTest {
    val model = ScriptedModel(mutableListOf(morning, evening))
    val out = summarizer(model).summarize(input())
    assertEquals(SummarySource.MODEL, out.source)
    assertEquals(good, out.text)
    assertTrue(out.rejectedReasons.isEmpty())
    assertNotNull(out.metrics)
    assertEquals(2, model.prompts.size) // no afternoon lines, no afternoon call
    assertTrue("08:30 · บ้าน · ออกจาก" in model.prompts[0] && "18:00" !in model.prompts[0])
    assertTrue("18:00 · มีนัด “ดินเนอร์กับแม่”" in model.prompts[1] && "08:30" !in model.prompts[1])
    assertTrue(model.prompts.all { "ไม่ใช้คำลงท้าย" in it })
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, repo.rows.getValue(day).provenance)
  }

  @Test fun `a failing part falls back alone and says why`() = runTest {
    val bad = "ช่วงเช้าออกจากบ้านตอน 07:45 แล้วไปถึงร้านกาแฟตอน 09:10"
    val out = summarizer(ScriptedModel(mutableListOf(bad, evening))).summarize(input())
    assertEquals(SummarySource.MODEL, out.source)
    assertTrue("morning: invented-time" in out.rejectedReasons)
    assertTrue("morning: invented-place" in out.rejectedReasons)
    assertTrue(out.text.startsWith("ช่วงเช้า เวลา 08:30 ออกจากบ้าน"))
    assertTrue(out.text.endsWith("$evening ครับ") || out.text.endsWith("${evening}ครับ"))
    assertFalse(out.text.contains("07:45"))
  }

  @Test fun `a long template part does not throw away the model parts`() = runTest {
    val busy = (0 until 8).map { "%02d:%02d ถึงที่ทำงาน ใช้เวลาเดินทางราว 40 นาที อยู่ราว 8 ชั่วโมงครับ".format(8 + it / 2, it * 5) } + lines.last()
    val out = summarizer(ScriptedModel(mutableListOf("ช่วงเช้าไปโรงพยาบาล", evening))).summarize(input(l = busy))
    assertEquals(SummarySource.MODEL, out.source, out.rejectedReasons.toString())
    assertTrue(out.text.length > SummaryChecker.MAX_CHARS && out.text.contains(evening))
  }

  @Test fun `every part failing gives the whole-day template, not stored`() = runTest {
    val out = summarizer(ScriptedModel(mutableListOf("ช่วงเช้าไปโรงพยาบาล", "ช่วงเย็นไปตลาด"))).summarize(input())
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertEquals(SummaryTemplate.paragraph(day, lines, krap), out.text)
    assertNull(repo.find(day))
  }

  @Test fun `the particle appears once, at the end`() = runTest {
    // a trailing particle on a part is dropped; one inside a part's text fails that part
    val out = summarizer(ScriptedModel(mutableListOf("${morning}ครับ", "ช่วงเย็นครับ มีนัด “ดินเนอร์กับแม่” ตอน 18:00ค่ะ"))).summarize(input())
    assertEquals(SummarySource.MODEL, out.source)
    assertTrue("evening: unexpected-particle" in out.rejectedReasons)
    assertEquals(1, Regex("ครับ").findAll(out.text).count())
    assertTrue(out.text.endsWith("ครับ"))
    assertFalse(out.text.contains("ค่ะ"))
  }

  @Test fun `trailing ka or kha particle on a part is dropped`() = runTest {
    val out = summarizer(ScriptedModel(mutableListOf("${morning}คะ", evening))).summarize(input(style = DiaryStyle(EndingParticle.KA)))
    assertEquals(SummarySource.MODEL, out.source)
    assertTrue(out.rejectedReasons.isEmpty())
    assertTrue(out.text.endsWith("ค่ะ"))
    assertEquals(1, Regex("ค่ะ").findAll(out.text).count())
    assertFalse(out.text.contains("คะ"))
  }

  @Test fun `three long parts stay under the whole-day cap`() = runTest {
    val day3 = listOf("08:30 ออกจากบ้านครับ", "13:00 ถึงที่ทำงานครับ", "19:00 มีนัด “ดินเนอร์กับแม่”ครับ")
    fun pad(s: String) = (s + " แล้วก็เล่าต่ออีกนิดหน่อย".repeat(20)).take(EveningSummarizer.partCap(3))
    val replies = mutableListOf(pad("ช่วงเช้าออกจากบ้านตอน 08:30"), pad("ช่วงบ่ายถึงที่ทำงานตอน 13:00"), pad("ช่วงเย็นมีนัด “ดินเนอร์กับแม่” ตอน 19:00"))
    val out = summarizer(ScriptedModel(replies)).summarize(input(l = day3))
    assertEquals(SummarySource.MODEL, out.source, out.rejectedReasons.toString())
    assertTrue(out.text.length <= SummaryChecker.MAX_CHARS)
    val tooLong = summarizer(ScriptedModel(mutableListOf(pad("ช่วงเช้าออกจากบ้านตอน 08:30") + "ก"))).summarize(input(regen = true, l = day3))
    assertTrue("morning: too-long" in tooLong.rejectedReasons)
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
    val model = ScriptedModel(mutableListOf(morning, evening, morning.replace("ช่วงเช้า", "ช่วงเช้าวันนี้"), evening))
    val s = summarizer(model)
    val first = s.summarize(input())
    val again = s.summarize(input())
    assertTrue(again.fromStore)
    assertEquals(first.text, again.text)
    assertEquals(2, model.prompts.size)
    val regenerated = s.summarize(input(regen = true))
    assertEquals(4, model.prompts.size)
    assertTrue(regenerated.text.startsWith("ช่วงเช้าวันนี้"))
    assertEquals(regenerated.text, repo.find(day)?.text)
    assertEquals(2, repo.saves)
  }

  @Test fun `model failure falls back to the template`() = runTest {
    val out = summarizer(BrokenModel()).summarize(input())
    assertEquals(SummarySource.TEMPLATE, out.source)
    assertEquals(listOf("morning: model-error: IllegalStateException", "evening: model-error: IllegalStateException"), out.rejectedReasons)
    assertTrue(out.text.isNotBlank())
  }

  @Test fun `prompt stays inside the token budget`() = runTest {
    val many = (1..40).map { "%02d:%02d ถึงที่ทำงานครับ".format((it / 2) % 12, it % 60) }
    val budget = 400
    val model = ScriptedModel(mutableListOf())
    summarizer(model, budget).summarize(input(l = many))
    assertTrue(model.prompts.isNotEmpty() && model.prompts.all { TokenEstimator.estimate(it) <= budget })
    val p = SummaryPromptBuilder.build(SummaryPromptInput(SummaryPart.MORNING, many, listOf("บริบท")), budget)
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
    val reply = "วันจันทร์ที่ 7 กันยายน พ.ศ. 2569 ช่วงเช้าออกจากบ้านตอน 08:30 แล้วถึงที่ทำงานตอน 09:10"
    val out = summarizer(ScriptedModel(mutableListOf(reply, evening))).summarize(input())
    assertEquals(SummarySource.MODEL, out.source)
    assertTrue(out.rejectedReasons.isEmpty(), out.rejectedReasons.toString())
  }

  @Test fun `a timeout does not poison the next model run`() = runTest {
    val s = EveningSummarizer(LocalModelClient(SlowThenFastModel(listOf(morning, evening))), repo, timeoutMillis = 1_000)
    val first = s.summarize(input())
    assertEquals(listOf("morning: timeout"), first.rejectedReasons)
    val second = s.summarize(input(regen = true))
    assertEquals(SummarySource.MODEL, second.source)
    assertTrue(second.rejectedReasons.isEmpty(), second.rejectedReasons.toString())
  }
}

private class SlowThenFastModel(private val replies: List<String>) : LocalModel {
  private var calls = 0
  override suspend fun load(config: ModelConfig) {}
  override fun generate(input: GenerateInput): Flow<String> = flow {
    val i = calls++
    if (i == 0) delay(10_000)
    emit(replies[i % replies.size])
  }
  override fun cancel() {}
  override suspend fun unload() {}
}
