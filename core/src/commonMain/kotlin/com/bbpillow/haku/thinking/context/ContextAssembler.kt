package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.language.Normalizer
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.TokenEstimator
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.index.SearchHitOutput
import com.bbpillow.haku.memory.index.SearchQueryInput
import com.bbpillow.haku.memory.index.SearchRepository

/**
 * The one place that builds model context (P1.9). Pure: reads memory + index, no model calls.
 *
 * Selection: standing instructions always; curated-core categories whose description (or facts) match the question
 * words; daily records by D18 order: date-word filter -> word search -> [semantic hook] -> [link hook].
 * Over budget: the lowest-value item goes first (standing instructions are highest value, so they go last).
 * Long-term levels only ever contribute records with canBePermanent provenance, even if a file was hand-edited.
 */
class ContextAssembler(
  private val memory: MemoryRepository,
  private val search: SearchRepository,
  private val pipeline: TextPipeline,
  private val synonyms: SynonymTable? = null,
  private val semantic: SemanticTier? = null,
  private val links: LinkTier? = null,
  private val limits: AssemblerLimits = AssemblerLimits(),
) {
  private class Candidate(val ref: MemoryRef, val record: MemoryRecord, val section: ContextSection) {
    var value = 0.0
    val tiers = linkedSetOf<RetrievalTier>()
    val reasons = mutableListOf<String>()
    fun add(tier: RetrievalTier, v: Double, reason: String) {
      tiers += tier
      reasons += reason
      value = maxOf(value, v)
    }
  }

  private class Pool {
    val items = linkedMapOf<MemoryRef, Candidate>()
    fun get(ref: MemoryRef, rec: MemoryRecord, section: ContextSection) = items.getOrPut(ref) { Candidate(ref, rec, section) }
  }

  fun assemble(input: AssembleInput): AssembledContext {
    val trace = mutableListOf<String>()
    val lang = input.languageCode
    val normalized = Normalizer.normalize(TextInput(input.question, lang)).normalized
    val range = TimeWords.find(normalized, input.nowEpochMs, input.utcOffsetMinutes)
    val words = contentWords(input.question, lang)
    val latest = LATEST_CUES.any { it in normalized }
    trace += "question words=$words range=${range?.word ?: "none"} latest=$latest"

    val standing = standingItems(trace)
    val pool = Pool()
    coreCandidates(words, lang, pool, trace)
    dailyCandidates(input, range, words, latest, pool, trace)
    val others = pool.items.values.map { toItem(it, input.utcOffsetMinutes) }
    return fit(input, standing, others, trace)
  }

  // --- standing: always ---
  private fun standingItems(trace: MutableList<String>): List<ContextItem> {
    val (ok, blocked) = memory.readStanding().partition { it.provenance.canBePermanent }
    if (blocked.isNotEmpty()) trace += "skipped ${blocked.size} standing records with non-permanent provenance"
    return ok.map { r ->
      item(ContextSection.STANDING, "- ${r.text}", MemoryRef(MemoryLevel.STANDING, "", r.id), r, listOf(RetrievalTier.ALWAYS), "standing instruction (always included)", Double.MAX_VALUE)
    }
  }

  // --- curated core: choose categories by description + question words, not whole files ---
  private fun coreCandidates(words: List<String>, lang: String, pool: Pool, trace: MutableList<String>) {
    if (words.isEmpty()) return
    val factHits = wordSearch(words, SearchQueryInput("", lang, limit = 20, levels = setOf(MemoryLevel.CORE)), trace)
      .filter { it.memory?.provenance?.canBePermanent == true }
    val scored = memory.listCategories().mapNotNull { cat ->
      val catText = cat.description + " " + cat.slug.replace('-', ' ')
      val catWords = pipeline.process(TextInput(catText, lang)).tokens.map { it.text.lowercase() }.toSet()
      val byDescription = words.count { w -> (synonyms?.expand(w) ?: setOf(w)).any { it.lowercase() in catWords } }
      val byFacts = factHits.count { it.memory!!.ref.group == cat.slug }
      (byDescription * 2 + byFacts).takeIf { it > 0 }?.let { Triple(cat.slug, it, byDescription) }
    }.sortedByDescending { it.second }.take(limits.maxCategories)
    trace += "core categories=${scored.map { it.first }}"
    for ((slug, score, byDesc) in scored) {
      val hitIds = factHits.filter { it.memory!!.ref.group == slug }.map { it.memory!!.ref.id }.toSet()
      val facts = memory.readCategory(slug).filter { it.provenance.canBePermanent }
      for (f in facts.filter { it.id in hitIds }) {
        pool.get(MemoryRef(MemoryLevel.CORE, slug, f.id), f, ContextSection.CORE)
          .add(RetrievalTier.WORDS, 45.0 + score, "core fact in '$slug' matched the question words")
      }
      // a description match with no fact hit shows the newest few facts, never the whole file
      if (byDesc > 0 || hitIds.isEmpty()) {
        for (f in facts.filter { it.id !in hitIds }.sortedByDescending { it.atEpochMs }.take(limits.maxOtherFactsPerCategory)) {
          pool.get(MemoryRef(MemoryLevel.CORE, slug, f.id), f, ContextSection.CORE)
            .add(RetrievalTier.CATEGORY, 30.0 + score, "category '$slug' matched by description")
        }
      }
    }
  }

  // --- daily: D18 order ---
  private fun dailyCandidates(input: AssembleInput, range: TimeRange?, words: List<String>, latest: Boolean, pool: Pool, trace: MutableList<String>) {
    // 1. structural: every record inside the date range
    if (range != null) {
      val inRange = TimeWords.utcDays(range)
        .flatMap { day -> memory.readDay(day).map { MemoryRef(MemoryLevel.DAILY, day, it.id) to it } }
        .filter { (_, r) -> r.atEpochMs >= range.fromEpochMs && r.atEpochMs < range.toEpochMs }
        .sortedBy { it.second.atEpochMs }
      inRange.forEachIndexed { i, (ref, r) ->
        pool.get(ref, r, ContextSection.RECALL).add(RetrievalTier.STRUCTURAL, 100.0 - i * 0.001, "date word '${range.word}'")
      }
      trace += "structural: ${inRange.size} records in range"
    }
    // 2. word search
    val base = SearchQueryInput("", input.languageCode, limit = limits.maxWordHits, levels = setOf(MemoryLevel.DAILY), fromEpochMs = range?.fromEpochMs, toEpochMs = range?.toEpochMs)
    val hits = wordSearch(words, base, trace).filter { it.memory != null }
    val ordered = if (latest) hits.sortedByDescending { it.memory!!.atEpochMs } else hits
    ordered.forEachIndexed { i, h ->
      val v = 50.0 + 10.0 * (1.0 - i.toDouble() / maxOf(1, ordered.size))
      addHit(h, RetrievalTier.WORDS, v, if (latest) "word match, newest first" else "word match rank ${i + 1}", pool)
    }
    // 3. semantic tier: disabled unless a hook is supplied; a failure falls back to the word results
    semantic?.let { tier ->
      try {
        tier.search(input.question, limits.maxWordHits).filter { it.memory?.ref?.level == MemoryLevel.DAILY }
          .forEach { addHit(it, RetrievalTier.SEMANTIC, 40.0, "semantic match", pool) }
      } catch (e: Exception) {
        trace += "semantic tier failed (${e::class.simpleName}: ${e.message}); using word results only"
      }
    }
    // 4. link following from the best word hits
    links?.let { tier ->
      val seeds = ordered.take(limits.maxLinkSeeds).mapNotNull { it.memory?.ref }
      if (seeds.isNotEmpty()) tier.follow(seeds).forEach { addHit(it, RetrievalTier.LINK, 20.0, "linked from a top hit", pool) }
    }
  }

  private fun addHit(h: SearchHitOutput, tier: RetrievalTier, value: Double, reason: String, pool: Pool) {
    val ref = h.memory?.ref ?: return
    val rec = memory.find(ref) ?: return // index entry outlived its file: the file wins
    pool.get(ref, rec, ContextSection.RECALL).add(tier, value, reason)
  }

  /** AND over all words; if that finds nothing, each word alone, merged (one stray word must not blank the result). */
  private fun wordSearch(words: List<String>, base: SearchQueryInput, trace: MutableList<String>): List<SearchHitOutput> {
    if (words.isEmpty()) return emptyList()
    val all = search.search(base.copy(text = words.joinToString(" ")))
    if (all.isNotEmpty() || words.size == 1) return all
    trace += "word search: AND of ${words.size} words found nothing, merging single-word results"
    return words.flatMap { search.search(base.copy(text = it)) }
      .groupBy { it.id }.map { (_, g) -> g.maxBy { it.score } }
      .sortedByDescending { it.score }.take(base.limit)
  }

  private fun contentWords(question: String, lang: String): List<String> =
    pipeline.process(TextInput(question, lang)).tokens.map { it.text }.filter { it !in STOP && it !in TimeWords.all }.distinct()

  // --- items and budget ---
  private fun toItem(c: Candidate, offsetMin: Int): ContextItem {
    val r = c.record
    val label = if (c.section == ContextSection.RECALL) {
      val iso = MemoryFormat.formatUtc(r.atEpochMs + offsetMin * 60_000L)
      val tag = if (r.provenance.canBePermanent) "" else " ${r.provenance.key}" // untrusted lines are marked for the model
      "- [${iso.substring(0, 10)} ${iso.substring(11, 16)}$tag] "
    } else {
      "- [${c.ref.group}] "
    }
    return item(c.section, label + r.text, c.ref, r, c.tiers.toList(), c.reasons.joinToString("; "), c.value)
  }

  private fun item(s: ContextSection, line: String, ref: MemoryRef, r: MemoryRecord, tiers: List<RetrievalTier>, reason: String, value: Double) =
    ContextItem(s, line, TokenEstimator.estimate("$line\n"), ref, r.provenance, r.atEpochMs, tiers, reason, value)

  private fun fit(input: AssembleInput, standing: List<ContextItem>, others: List<ContextItem>, trace: MutableList<String>): AssembledContext {
    val kept = (standing + others).toMutableList()
    val dropped = mutableListOf<ContextItem>()
    var total = kept.sumOf { it.tokens }
    // lowest value goes first; ties: earliest in insertion order (oldest standing, earliest line)
    while (total > input.budgetTokens && kept.isNotEmpty()) {
      val worst = kept.withIndex().minWith(compareBy<IndexedValue<ContextItem>>({ it.value.value }, { it.index })).value
      kept.remove(worst)
      dropped += worst
      total -= worst.tokens
    }
    if (dropped.isNotEmpty()) trace += "over budget (${input.budgetTokens}): dropped ${dropped.size} lowest-value items"
    val ordered = kept.sortedWith(compareBy<ContextItem> { it.section.ordinal }.thenBy { if (it.section == ContextSection.RECALL) it.atEpochMs else 0L })
    return AssembledContext(input.question, input.budgetTokens, ordered, dropped, trace)
  }

  private companion object {
    val LATEST_CUES = listOf("ล่าสุด", "สุดท้าย")

    /** Question filler that never helps retrieval. Grow from real queries. */
    val STOP = setOf(
      "ไป", "ไหน", "มา", "บ้าง", "เมื่อ", "ไร", "เมื่อไร", "เมื่อไหร่", "เมื่อใด", "คือ", "ครั้ง", "ล่าสุด", "สุดท้าย",
      "ที่", "ได้", "ไหม", "หรือ", "อะไร", "ทำ", "ยัง", "แล้ว", "ของ", "ฉัน", "ผม", "เรา", "ให้", "ช่วย", "บอก", "หน่อย",
      "ครับ", "ค่ะ", "คะ", "นะ", "เป็น", "มี", "จำ", "the", "a", "is", "what", "when",
    )
  }
}
