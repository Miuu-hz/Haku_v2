package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.thinking.TextEmbedder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Fake embedder: one axis per concept, picked by keyword, so paraphrases share a direction with no shared words.
 * Unknown text points along the last axis (unrelated to everything). Question words are in RecallRig.VOCAB so the
 * word tiers see real words that no line contains.
 */
private class FakeEmbedder : TextEmbedder {
  val calls = mutableListOf<List<String>>()
  override val dims = 4
  override suspend fun embed(texts: List<String>): List<FloatArray> {
    calls += texts
    return texts.map { t ->
      when {
        "สุขภาพ" in t -> floatArrayOf(1f, 0.6f, 0f, 0f) // near the runs (cos 0.90), weakly near cafes (cos 0.51)
        "วิ่ง" in t -> floatArrayOf(1f, 0.1f, 0f, 0f)
        listOf("กาแฟ", "คาเฟ่").any { it in t } -> floatArrayOf(0f, 1f, 0f, 0f)
        "ประชุม" in t -> floatArrayOf(0f, 0f, 1f, 0f)
        else -> floatArrayOf(0f, 0f, 0f, 1f)
      }
    }
  }
}

class SemanticRecallTest {
  private val rig = RecallRig()
  private val embedder = FakeEmbedder()
  private val indexer = VectorIndexer(rig.memory, rig.vectors, MODEL)

  @AfterTest fun close() = rig.close()

  private fun seed() {
    rig.daily("ไปวิ่งสวนลุมพินี", "2026-09-28T18:00:00.000Z")
    rig.daily("ประชุมกับทีมที่ออฟฟิศ", "2026-09-29T02:00:00.000Z")
    rig.daily("ไปกินข้าวที่ร้านกาแฟ", "2026-09-29T05:00:00.000Z")
    rig.daily("ไปวิ่งอีกรอบ", "2026-09-30T01:00:00.000Z")
  }

  private suspend fun tier(q: String, min: Float = VectorSemanticTier.MIN_COSINE): SemanticTier {
    val v = embedder.embed(listOf(q)).single()
    return VectorSemanticTier(rig.vectors, MODEL, v, min)
  }

  private fun ask(q: String, t: SemanticTier?, budget: Int = 2000) = rig.assembler.assemble(AssembleInput(q, budget, RecallRig.NOW, RecallRig.OFFSET_MIN), t)

  // --- indexing job ---

  @Test fun `indexing embeds missing lines newest first, respects the cap, skips existing`() = runTest {
    seed()
    val first = indexer.run(embedder, cap = 3)
    assertEquals(VectorIndexOutput(indexed = 3, pruned = 0, pending = 1), first)
    assertEquals(listOf("ไปวิ่งอีกรอบ", "ไปกินข้าวที่ร้านกาแฟ", "ประชุมกับทีมที่ออฟฟิศ"), embedder.calls.flatten())

    embedder.calls.clear()
    assertEquals(VectorIndexOutput(1, 0, 0), indexer.run(embedder))
    assertEquals(listOf("ไปวิ่งสวนลุมพินี"), embedder.calls.flatten())

    embedder.calls.clear()
    assertEquals(VectorIndexOutput(0, 0, 0), indexer.run(embedder))
    assertTrue(embedder.calls.isEmpty(), "nothing pending = no model call")
    assertEquals(4, rig.vectors.count(MODEL))
  }

  @Test fun `indexing prunes vectors of lines gone from the files and of other models`() = runTest {
    seed()
    indexer.run(embedder)
    rig.fs.delete(rig.root / "daily" / "2026-09-30.md") // file removed behind the repository's back
    val out = VectorIndexer(rig.memory, rig.vectors, MODEL).run(embedder)
    assertEquals(1, out.pruned)
    assertEquals(3, rig.vectors.count(MODEL))

    val other = VectorIndexer(rig.memory, rig.vectors, "other-model").run(embedder)
    assertEquals(VectorIndexOutput(3, 3, 0), other)
    assertEquals(0, rig.vectors.count(MODEL))
  }

  @Test fun `a line deleted while embedding is not written back`() = runTest {
    val line = rig.daily("ไปวิ่งสวนลุมพินี", "2026-09-29T02:00:00.000Z")
    val (todo, _, _) = indexer.pending(10)
    rig.memory.deleteDaily("2026-09-29", line.id)
    assertEquals(0, indexer.save(todo, embedder.embed(todo.map { it.text })))
    assertEquals(0, rig.vectors.count())
  }

  // --- chat-time tier in the assembler ---

  @Test fun `paraphrase with no shared word is found by the semantic tier`() = runTest {
    seed()
    indexer.run(embedder)
    val q = "สุขภาพ"
    assertTrue(ask(q, null).items.isEmpty(), "words alone find nothing")
    val c = ask(q, tier(q))
    assertEquals(setOf("ไปวิ่งสวนลุมพินี", "ไปวิ่งอีกรอบ"), c.items.map { it.text.substringAfter("] ") }.toSet(), c.promptText())
    assertTrue(c.items.all { it.tiers == listOf(RetrievalTier.SEMANTIC) && "cos" in it.reason })
    assertTrue(c.trace.any { it.startsWith("semantic:") })
  }

  @Test fun `threshold keeps weak matches out`() = runTest {
    seed()
    indexer.run(embedder)
    assertTrue(ask("สุขภาพ", tier("สุขภาพ", min = 0.95f)).items.isEmpty())
    // the cafe line sits at cos 0.51, under the default floor (asserted exactly in the paraphrase test); unrelated = cos 0
    assertTrue(ask("ซื้อ", tier("ซื้อ")).items.isEmpty())
  }

  @Test fun `semantic lines rank below word hits and fit the budget`() = runTest {
    seed()
    indexer.run(embedder)
    val q = "ร้านกาแฟ สุขภาพ" // words find the cafe line; the semantic tier adds the runs
    val full = ask(q, tier(q))
    val cafe = full.items.single { "ร้านกาแฟ" in it.text }
    assertTrue(RetrievalTier.WORDS in cafe.tiers)
    val semantic = full.items.filter { it.tiers == listOf(RetrievalTier.SEMANTIC) }
    assertTrue(semantic.isNotEmpty())
    assertTrue(semantic.all { it.value < cafe.value })

    val tight = ask(q, tier(q), budget = cafe.tokens)
    assertTrue(tight.usedTokens <= cafe.tokens)
    assertEquals(listOf(cafe.text), tight.items.map { it.text })
  }

  @Test fun `date questions keep the structural answer and semantic stays inside the range`() = runTest {
    seed()
    indexer.run(embedder)
    val q = "เมื่อวาน สุขภาพ"
    val c = ask(q, tier(q))
    // yesterday (local) = 09-29 local: the 01:00 run is in range (already structural, only tagged), today's run is not
    assertTrue(c.items.none { "ไปวิ่งอีกรอบ" in it.text }, c.promptText())
    assertEquals(3, c.items.count { RetrievalTier.STRUCTURAL in it.tiers })
    val run = c.items.single { "สวนลุมพินี" in it.text }
    assertEquals(listOf(RetrievalTier.STRUCTURAL, RetrievalTier.SEMANTIC), run.tiers)
  }

  @Test fun `no vectors means no tier and the old behaviour`() = runTest {
    seed()
    assertNull(VectorSemanticTier.forQuestion("สุขภาพ", embedder, rig.vectors, MODEL))
    assertTrue(embedder.calls.isEmpty(), "no vectors = the question is not embedded")
    val old = rig.ask("เมื่อวานไปไหนมา")
    val withNull = ask("เมื่อวานไปไหนมา", null)
    assertEquals(old.items, withNull.items)
    indexer.run(embedder)
    val t = VectorSemanticTier.forQuestion("สุขภาพ", embedder, rig.vectors, MODEL)!!
    assertTrue(t.search("", 8).all { it.memory!!.ref.level == MemoryLevel.DAILY && it.score >= VectorSemanticTier.MIN_COSINE })
  }

  private companion object {
    const val MODEL = "fake-embedder"
  }
}
