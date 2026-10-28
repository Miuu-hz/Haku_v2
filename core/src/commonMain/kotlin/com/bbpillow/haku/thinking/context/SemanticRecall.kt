package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.index.IndexedMemory
import com.bbpillow.haku.memory.index.MatchKind
import com.bbpillow.haku.memory.index.SearchHitOutput
import com.bbpillow.haku.memory.index.VectorRecord
import com.bbpillow.haku.memory.index.VectorRepository
import com.bbpillow.haku.thinking.TextEmbedder
import com.bbpillow.haku.thinking.cosine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.withContext

/** A daily line waiting for its vector. */
data class EmbedLineRecord(val memory: IndexedMemory, val text: String)

/** One indexing run: [indexed] new vectors, [pruned] dropped (line gone or other model), [pending] still to embed after this run. */
data class VectorIndexOutput(val indexed: Int, val pruned: Int, val pending: Int)

/**
 * Background embedding of daily lines (ชิ้น ค), nightly and after bulk writes. Files are truth: vectors of lines no
 * longer in the files are dropped, lines without a vector are embedded newest first, [cap] per run. Store calls run in
 * [db] (the DB thread); the embedder runs outside it, and a line deleted meanwhile is not written back.
 */
class VectorIndexer(
  private val memory: MemoryRepository,
  private val vectors: VectorRepository,
  private val model: String,
) {
  suspend fun run(embedder: TextEmbedder, cap: Int = DEFAULT_CAP, db: CoroutineContext = EmptyCoroutineContext): VectorIndexOutput {
    val (todo, pruned, waiting) = withContext(db) { pending(cap) }
    var indexed = 0
    for (chunk in todo.chunked(BATCH)) {
      val vecs = embedder.embed(chunk.map { it.text })
      indexed += withContext(db) { save(chunk, vecs) }
    }
    return VectorIndexOutput(indexed, pruned, waiting - indexed)
  }

  /** DB thread: prune, then up to [cap] lines without a vector. Returns (lines, pruned, all lines still missing). */
  fun pending(cap: Int): Triple<List<EmbedLineRecord>, Int, Int> {
    var pruned = vectors.retainModel(model)
    val live = memory.listDays().sortedDescending().flatMap { day ->
      memory.readDay(day).sortedByDescending { it.atEpochMs }.map { r ->
        EmbedLineRecord(IndexedMemory(MemoryRef(MemoryLevel.DAILY, day, r.id), r.provenance, r.atEpochMs), r.text)
      }
    }
    val have = vectors.ids(model)
    val liveKeys = live.mapTo(HashSet()) { it.memory.ref.key }
    pruned += vectors.removeAll(have.filter { it !in liveKeys })
    val missing = live.filter { it.memory.ref.key !in have && it.text.isNotBlank() }
    return Triple(missing.take(cap), pruned, missing.size)
  }

  /** DB thread: stores the vectors whose line still exists (a delete during the embed wins). */
  fun save(lines: List<EmbedLineRecord>, vecs: List<FloatArray>): Int {
    require(lines.size == vecs.size) { "${lines.size} lines, ${vecs.size} vectors" }
    val keep = lines.zip(vecs).filter { (l, _) -> memory.find(l.memory.ref) != null }
    vectors.upsert(keep.map { (l, v) -> VectorRecord(l.memory, model, v) })
    return keep.size
  }

  companion object {
    const val DEFAULT_CAP = 500
    private const val BATCH = 16
  }
}

/**
 * The chat-time half: the question is embedded once ([queryVector], see [forQuestion]), lines are ranked by cosine
 * against the stored vectors (the String passed to [search] is ignored). [minCosine] and the limit keep weak matches out.
 * ponytail: brute-force scan of every vector per question (~25 MB at 8k lines × 768 dims); add an ANN index past that.
 */
class VectorSemanticTier(
  private val vectors: VectorRepository,
  private val model: String,
  private val queryVector: FloatArray,
  private val minCosine: Float = MIN_COSINE,
) : SemanticTier {
  override fun search(question: String, limit: Int): List<SearchHitOutput> =
    vectors.all(model).asSequence()
      .filter { it.vector.size == queryVector.size }
      .map { it to cosine(queryVector, it.vector) }
      .filter { it.second >= minCosine }
      .sortedByDescending { it.second }
      .take(limit)
      .map { (r, s) -> SearchHitOutput(r.memory.ref.key, "", s.toDouble(), MatchKind.SEMANTIC, r.memory) }
      .toList()

  companion object {
    /** Tunable: on-device Thai paraphrases sat well above this, unrelated lines below (EMBED spike). */
    const val MIN_COSINE = 0.78f // Fold5: unrelated pairs ~0.72, paraphrases ~0.85-0.90

    /** Embeds [question] and returns the tier, or null when nothing is embedded yet (then no model work at all). */
    suspend fun forQuestion(
      question: String,
      embedder: TextEmbedder,
      vectors: VectorRepository,
      model: String,
      db: CoroutineContext = EmptyCoroutineContext,
    ): SemanticTier? {
      if (withContext(db) { vectors.count(model) } == 0) return null
      return VectorSemanticTier(vectors, model, embedder.embed(listOf(question.trim())).single())
    }
  }
}
