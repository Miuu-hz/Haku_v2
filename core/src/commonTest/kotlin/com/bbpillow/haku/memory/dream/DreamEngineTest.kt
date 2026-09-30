package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.WordSegmenter
import com.bbpillow.haku.language.WordToken
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class DreamEngineTest {
  private val fs = FakeFileSystem()
  private var n = 0
  private val store = MemoryStore(fs, "/m".toPath()) { "id${++n}" }
  private val pipeline = TextPipeline(object : WordSegmenter {
    override fun segment(text: String, languageCode: String) = listOf(WordToken(text, 0, text.length))
  })
  private val night = "2026-09-30"
  private val day0 = requireNotNull(MemoryFormat.parseUtc("2026-09-01T00:00:00.000Z"))

  private fun at(dayOfMonth: Int, hour: Int = 9) = day0 + (dayOfMonth - 1) * 86_400_000L + hour * 3_600_000L
  private fun add(d: Int, text: String, p: Provenance = Provenance.INFERRED_FROM_USER_DATA, hour: Int = 9) =
    store.appendDaily(DailyEventInput(text, p, at(d, hour)))

  private fun engine(cfg: DreamConfig = DreamConfig(), phraser: FactPhraser? = null) = DreamEngine(store, pipeline, cfg, phraser)
  private fun allCore() = store.listCategories().flatMap { c -> store.readCategory(c.slug).map { c.slug to it } }

  /** Thirty simulated days: a gym habit, a dentist series, a user-typed preference, an external-only place, noise. */
  private fun seedMonth() {
    for (d in 1..30 step 2) add(d, "0${d % 9 + 1}:15 ถึงโรงยิม ใช้เวลาเดินทางราว ${10 + d} นาที อยู่ราว 1 ชั่วโมง")
    for (d in listOf(3, 10, 24)) add(d, "15:00 มีนัด “หมอฟัน” ถึง 16:00")
    for (d in listOf(5, 12, 19, 26)) add(d, "ชอบกาแฟดำ", Provenance.USER_TYPED, hour = 7)
    for (d in 1..10) add(d, "ถึงคาเฟ่ลับ", Provenance.EXTERNAL)
    add(7, "ถึงสถานที่หนึ่ง"); add(8, "ซื้อนม", Provenance.USER_TYPED); add(9, "ถึงบ้าน")
    add(4, "ถึงบ้าน"); add(6, "ถึงบ้าน"); add(11, "ถึงบ้าน")
  }

  @Test fun `thirty simulated days yield correct facts, no duplicates, within budget`() = runTest {
    seedMonth()
    val out = engine().dream(DreamInput(night))
    val core = allCore()
    val texts = core.map { it.second.text }
    assertEquals(setOf("ไปโรงยิมเป็นประจำ", "มีนัด “หมอฟัน” เป็นประจำ", "ชอบกาแฟดำ"), texts.toSet())
    assertEquals(texts.size, texts.toSet().size)
    assertTrue(core.size <= DreamConfig().maxCoreFacts)
    assertEquals(CoreCategories.YOU, core.first { it.second.text == "ชอบกาแฟดำ" }.first)
    assertEquals(CoreCategories.AREAS, core.first { it.second.text.startsWith("ไปโรงยิม") }.first)
    assertEquals(3, out.promoted.size)
    assertTrue(texts.none { "คาเฟ่ลับ" in it || "สถานที่หนึ่ง" in it || "บ้าน" in it })
  }

  @Test fun `promoted facts keep the provenance of their source`() = runTest {
    seedMonth()
    engine().dream(DreamInput(night))
    val byText = allCore().associate { it.second.text to it.second.provenance }
    assertEquals(Provenance.USER_TYPED, byText["ชอบกาแฟดำ"])
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, byText["ไปโรงยิมเป็นประจำ"])
  }

  @Test fun `external or system records never reach long-term memory however often they recur`() = runTest {
    for (d in 1..20) add(d, "ถึงคาเฟ่ลับ", Provenance.EXTERNAL)
    for (d in 1..20) add(d, "ชอบชาเขียว", Provenance.SYSTEM) // SYSTEM is never a note theme source, but must not leak either
    for (d in 1..4) add(d, "มีนัด “ทันตแพทย์” ถึง 10:00", Provenance.EXTERNAL)
    for (d in 5..6) add(d, "มีนัด “ทันตแพทย์” ถึง 10:00") // two user-side days + four external: still not enough user days
    val out = engine().dream(DreamInput(night))
    assertTrue(allCore().isEmpty())
    assertTrue(store.readStanding().isEmpty())
    assertTrue(out.promoted.isEmpty())
    val held = out.heldBack.first { it.subject == "คาเฟ่ลับ" }
    assertEquals(HoldReason.PROVENANCE, held.reason)
    assertEquals(HoldReason.PROVENANCE, out.heldBack.first { it.subject == "ทันตแพทย์" }.reason)
  }

  @Test fun `promotion source is always an eligible record even when external records are newer`() = runTest {
    for (d in 26..28) add(d, "ถึงโรงยิม")
    add(29, "ถึงโรงยิม", Provenance.EXTERNAL)
    val out = engine().dream(DreamInput(night))
    val fact = out.promoted.single()
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, allCore().single().second.provenance)
    assertEquals(3, fact.sourceIds.size) // the external record is not cited as support
  }

  @Test fun `a fact the user forgot is never promoted again`() = runTest {
    for (d in listOf(20, 22, 25)) add(d, "ถึงโรงยิม")
    engine().dream(DreamInput("2026-09-26"))
    val (cat, fact) = allCore().single()
    assertTrue(store.deleteCoreFact(cat, fact.id))

    for (d in 27..29) add(d, "ถึงโรงยิม") // even more evidence afterwards
    val second = engine().dream(DreamInput("2026-09-29")) // a fresh engine, as a new nightly job would be
    assertTrue(allCore().isEmpty())
    assertEquals(HoldReason.FORGOTTEN, second.heldBack.single().reason)
    assertContains(second.report, "คุณเคยลบข้อเท็จจริงนี้แล้ว")

    engine().dream(DreamInput(night))
    assertTrue(allCore().isEmpty())
  }

  @Test fun `a rejected theme is never promoted`() = runTest {
    for (d in 25..29) add(d, "ถึงโรงยิม")
    val e = engine()
    e.rejectTheme("PLACE:โรงยิม")
    val out = engine().dream(DreamInput(night))
    assertTrue(allCore().isEmpty())
    assertEquals(HoldReason.REJECTED, out.heldBack.single().reason)
  }

  @Test fun `same night twice changes nothing, later nights do not re-promote`() = runTest {
    seedMonth()
    val first = engine().dream(DreamInput(night))
    val coreAfter = allCore()
    val again = engine().dream(DreamInput(night))
    assertTrue(again.alreadyRan)
    assertEquals(first.report, again.report)
    assertEquals(coreAfter, allCore())

    val next = engine().dream(DreamInput("2026-10-01"))
    assertTrue(next.promoted.isEmpty() && next.tidied.isEmpty())
    assertEquals(coreAfter, allCore())
  }

  @Test fun `tidy removes system copies and exact user duplicates but keeps different user originals`() = runTest {
    val a = add(1, "ซื้อนม", Provenance.USER_TYPED)
    val exact = add(1, "ซื้อนม", Provenance.USER_TYPED)
    val spaced = add(1, "ซื้อนม  ", Provenance.USER_TYPED) // near-identical user original: untouched
    val copy = add(1, "ซื้อนม", Provenance.INFERRED_FROM_USER_DATA)
    val i1 = store.addIntention(NoteInput("โทรหาแม่", Provenance.USER_TYPED, at(1)))
    val i2 = store.addIntention(NoteInput("โทรหาแม่", Provenance.USER_TYPED, at(2)))
    val out = engine().dream(DreamInput(night))
    val gone = out.tidied.map { it.id }.toSet()
    assertEquals(setOf(exact.id, copy.id, i2.id), gone)
    assertEquals(listOf(a.id, spaced.id), store.readDay(MemoryFormat.dayOf(at(1))).map { it.id })
    assertEquals(listOf(i1.id), store.readIntentions().map { it.id })
    assertTrue(out.tidied.first { it.id == exact.id }.exact)
    assertContains(out.report, "ลบ “ซื้อนม”")
  }

  @Test fun `tidy is cancelled when it would delete too much`() = runTest {
    repeat(12) { add(1, "ซ้ำ", Provenance.INFERRED_FROM_USER_DATA) }
    val out = engine().dream(DreamInput(night))
    assertTrue(out.tidyCancelled)
    assertEquals(12, store.readDay(MemoryFormat.dayOf(at(1))).size)
    assertContains(out.report, "ยกเลิกการจัดของ")
  }

  @Test fun `work is bounded by maxRecords and newest days are read first`() = runTest {
    for (d in 1..30) { add(d, "ถึงโรงยิม"); add(d, "ถึงโรงยิม ใช้เวลาเดินทางราว 5 นาที") }
    val out = engine(DreamConfig(maxRecords = 10)).dream(DreamInput(night))
    assertTrue(out.truncated)
    assertEquals(5, out.promoted.single().days) // 10 records = 5 newest days
    assertContains(out.report, "ถึงขีดจำกัดงานต่อคืน")
  }

  @Test fun `per-night promotion budget holds the rest back for later`() = runTest {
    for (d in 25..29) { add(d, "ถึงโรงยิม"); add(d, "ถึงตลาด"); add(d, "ถึงวัด") }
    val out = engine(DreamConfig(maxPromotionsPerRun = 1)).dream(DreamInput(night))
    assertEquals(1, out.promoted.size)
    assertEquals(listOf(HoldReason.BUDGET, HoldReason.BUDGET), out.heldBack.map { it.reason })
  }

  @Test fun `core size budget blocks promotion`() = runTest {
    for (d in 25..29) add(d, "ถึงโรงยิม")
    store.writeCoreFact(com.bbpillow.haku.memory.CoreFactInput("you", "x", Provenance.USER_TYPED, 0))
    val out = engine(DreamConfig(maxCoreFacts = 1)).dream(DreamInput(night))
    assertEquals(HoldReason.BUDGET, out.heldBack.single().reason)
  }

  @Test fun `a fact the user already wrote is not duplicated`() = runTest {
    for (d in 25..29) add(d, "ถึงโรงยิม")
    store.writeCoreFact(com.bbpillow.haku.memory.CoreFactInput("areas", "ไปโรงยิมเป็นประจำ", Provenance.USER_TYPED, 0))
    val out = engine().dream(DreamInput(night))
    assertEquals(HoldReason.ALREADY_KNOWN, out.heldBack.single().reason)
    assertEquals(1, allCore().size)
  }

  @Test fun `too few days and low score are held back with reasons`() = runTest {
    for (d in listOf(1, 2)) add(d, "ถึงโรงยิม") // 2 days < 3
    for (d in listOf(1, 2, 3)) add(d, "ถึงวัด") // old: 3 days but 27 days ago
    val out = engine(DreamConfig(minScore = 0.6)).dream(DreamInput(night))
    val reasons = out.heldBack.associate { it.subject to it.reason }
    assertEquals(HoldReason.TOO_FEW_DAYS, reasons["โรงยิม"])
    assertEquals(HoldReason.LOW_SCORE, reasons["วัด"])
    assertTrue(allCore().isEmpty())
  }

  @Test fun `model phrase is used when the checker passes`() = runTest {
    for (d in 26..29) add(d, "ถึงโรงยิม")
    val out = engine(phraser = FactPhraser { "ออกกำลังกายที่โรงยิมเป็นประจำ" }).dream(DreamInput(night))
    assertEquals("ออกกำลังกายที่โรงยิมเป็นประจำ", out.promoted.single().text)
    assertEquals("ออกกำลังกายที่โรงยิมเป็นประจำ", allCore().single().second.text)
  }

  @Test fun `invented names, numbers, throwing or null model fall back to the template`() = runTest {
    for (phrase in listOf<suspend () -> String?>({ "ไปโรงยิม 5 ครั้งต่อสัปดาห์" }, { "ไปโรงยิม Fitness First" }, { "ไปยิมเป็นประจำ" }, { null }, { error("boom") })) {
      val fs2 = FakeFileSystem()
      val s = MemoryStore(fs2, "/m".toPath()) { "i${++n}" }
      for (d in 26..29) s.appendDaily(DailyEventInput("ถึงโรงยิม", Provenance.INFERRED_FROM_USER_DATA, at(d)))
      val out = DreamEngine(s, pipeline, phraser = FactPhraser { phrase() }).dream(DreamInput(night))
      assertEquals("ไปโรงยิมเป็นประจำ", out.promoted.single().text, "phrase rejected")
    }
  }

  @Test fun `checker enforces support and unsupported numbers`() {
    val src = listOf("ถึงโรงยิม ใช้เวลา 10 นาที")
    assertTrue(DreamFactChecker.check("ไปโรงยิมเป็นประจำ", "โรงยิม", src, 3, 3).isEmpty())
    assertEquals(listOf("unsupported"), DreamFactChecker.check("ไปโรงยิมเป็นประจำ", "โรงยิม", src, 2, 3))
    assertContains(DreamFactChecker.check("ไปโรงยิม 99 ครั้ง", "โรงยิม", src, 3, 3), "invented-number")
    assertTrue(DreamFactChecker.check("ไปโรงยิม 10 นาที", "โรงยิม", src, 3, 3).isEmpty())
    assertContains(DreamFactChecker.check("ไปโรงยิมเป็นประจำ", "ฟิตเนส", src, 3, 3), "missing-subject")
    assertContains(DreamFactChecker.check("ไปโรงยิม“ลับ”", "โรงยิม", src, 3, 3), "invented-quote")
    assertContains(DreamFactChecker.check("ไปโรงยิม​", "โรงยิม", src, 3, 3), "control-chars")
    assertContains(DreamFactChecker.check("", "โรงยิม", src, 3, 3), "empty")
    assertContains(DreamFactChecker.check("โรงยิม" + "ก".repeat(200), "โรงยิม", src, 3, 3), "too-long")
  }

  @Test fun `report is written to the dreams level in Thai with source ids and reasons`() = runTest {
    for (d in 26..29) add(d, "ถึงโรงยิม")
    val srcIds = store.readDay(MemoryFormat.dayOf(at(26))).map { it.id } + store.readDay(MemoryFormat.dayOf(at(29))).map { it.id }
    add(26, "ถึงวัด", Provenance.EXTERNAL); add(27, "ถึงวัด", Provenance.EXTERNAL); add(28, "ถึงวัด", Provenance.EXTERNAL)
    val dup = add(27, "ถึงโรงยิม")
    val out = engine().dream(DreamInput(night))
    assertEquals(out.report, store.readDreamReport(night))
    assertContains(out.report, "รายงานการย่อยความจำ คืนวันที่ $night")
    assertContains(out.report, "เลื่อนขั้นเป็นความจำถาวร")
    assertContains(out.report, "“ไปโรงยิมเป็นประจำ” (หมวด areas) เพราะพบซ้ำใน 4 วัน")
    srcIds.forEach { assertContains(out.report, it) }
    assertContains(out.report, "ลบ “ถึงโรงยิม” (บันทึกวันที่ ${MemoryFormat.dayOf(at(27))} รหัส ${dup.id})")
    assertContains(out.report, "“วัด” ที่มาไม่ใช่ข้อมูลของผู้ใช้")
    assertFalse(out.alreadyRan)
  }

  @Test fun `empty memory produces an empty but valid report`() = runTest {
    val out = engine().dream(DreamInput(night))
    assertEquals("ไม่มีรายการซ้ำ", out.report.lines().first { "ไม่มีรายการซ้ำ" in it }.removePrefix("- "))
    assertTrue(out.promoted.isEmpty() && out.heldBack.isEmpty())
  }

  @Test fun `bad day is rejected`() = runTest {
    kotlin.test.assertFailsWith<IllegalArgumentException> { engine().dream(DreamInput("nope")) }
  }
}
