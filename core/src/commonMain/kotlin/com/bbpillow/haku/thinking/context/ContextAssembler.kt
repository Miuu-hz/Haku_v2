package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.language.Normalizer
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.language.TokenEstimator
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.index.SearchHitOutput
import com.bbpillow.haku.memory.index.SearchQueryInput
import com.bbpillow.haku.memory.index.SearchRepository

/**
 * The one place that builds model context (P1.9). Pure: reads memory + index, no model calls.
 *
 * Selection: standing instructions always; the "ตอนนี้" line when [AssembleInput.now] is given; curated-core categories whose description (or facts) match the question
 * words; daily records by D18 order: date-word filter -> word search -> semantic (embedded question, when a
 * [SemanticTier] is given) -> [link hook]. Semantic lines only add what words missed, rank below them, and stay inside
 * the asked date range, so date questions keep their structural/word answer.

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

  /** [semantic] = this question's tier (its vector is embedded per question); defaults to the constructor's. */
  fun assemble(input: AssembleInput, semantic: SemanticTier? = this.semantic): AssembledContext {
    val trace = mutableListOf<String>()
    val lang = input.languageCode
    val normalized = Normalizer.normalize(TextInput(input.question, lang)).normalized
    val range = TimeWords.find(normalized, input.nowEpochMs, input.utcOffsetMinutes)
    val words = contentWords(input.question, lang)
    val latest = LATEST_CUES.any { it in normalized }
    trace += "question words=$words range=${range?.word ?: "none"} latest=$latest"

    // Only for questions about now: on every question the line (today's appointments) crowded out the asked day
    // (Fold5 eval 2026-10-29: 94% -> 68% with it always on).
    val isPast = PAST_CUES.any { it in normalized } && LOCATION_NOW_CUES.none { it in normalized }
    val now = input.now?.takeIf { !isPast && NOW_CUES.any { cue -> cue in normalized } }
    val standing = standingItems(trace) + listOfNotNull(now?.let { nowItem(it, input, trace) })
    val pool = Pool()
    val fallback = range == null && words.isEmpty()
    coreCandidates(words, lang, pool, trace)
    dailyCandidates(input, range, words, latest, fallback, pool, trace)
    semantic?.let { semanticCandidates(it, input.question, range, pool, trace) }
    val others = pool.items.values.map { toItem(it, input.utcOffsetMinutes) }
    return fit(input, standing, others, trace, newestFirst = fallback)
  }

  // --- standing: always ---
  private fun standingItems(trace: MutableList<String>): List<ContextItem> {
    val (ok, blocked) = memory.readStanding().partition { it.provenance.canBePermanent }
    if (blocked.isNotEmpty()) trace += "skipped ${blocked.size} standing records with non-permanent provenance"
    return ok.map { r ->
      item(ContextSection.STANDING, "- ${r.text}", MemoryRef(MemoryLevel.STANDING, "", r.id), r, listOf(RetrievalTier.ALWAYS), "standing instruction (always included)", Double.MAX_VALUE)
    }
  }

  // --- now: local date/time, where the user is, what is left today (P1.9) ---
  private fun nowItem(s: NowStateInput, input: AssembleInput, trace: MutableList<String>): ContextItem {
    val offMs = input.utcOffsetMinutes * 60_000L
    fun hhmm(ms: Long) = MemoryFormat.formatUtc(ms + offMs).substring(11, 16)
    val parts = mutableListOf("${ThaiDateFormat.dayHeader(MemoryFormat.dayOf(input.nowEpochMs + offMs))} เวลา ${hhmm(input.nowEpochMs)}")
    s.placeName?.let { p -> parts += "อยู่" + (if (p.startsWith("ที่")) p else "ที่$p") + (s.sinceUtcMs?.let { " ตั้งแต่ ${hhmm(it)}" } ?: "") }
    // "มีนัด" on purpose: the chat prompt counts appointments only from lines that say it
    if (s.remaining.isNotEmpty()) {
      parts += "วันนี้ยังมีนัด " + s.remaining.joinToString(", ") { a -> "${hhmm(a.startUtcMs)} “${a.title}”" + (a.endUtcMs?.let { " ถึง ${hhmm(it)}" } ?: "") }
    } else if (s.hasAppointmentsToday) {
      parts += "นัดวันนี้หมดแล้ว"
    }
    if (s.lastLeftPlace != null && s.lastLeftUtcMs != null) parts += "ล่าสุดออกจาก${s.lastLeftPlace} เวลา ${hhmm(s.lastLeftUtcMs)}"
    val line = "- [$NOW_LABEL] " + parts.joinToString(" · ")
    trace += "now: ${parts.size} parts from ${s.sourceEventIds.size} capture events"
    return ContextItem(
      ContextSection.NOW, line, TokenEstimator.estimate("$line\n"), MemoryRef(MemoryLevel.DAILY, MemoryFormat.dayOf(input.nowEpochMs), NOW_ID),
      Provenance.INFERRED_FROM_USER_DATA, input.nowEpochMs, listOf(RetrievalTier.ALWAYS),
      "current state (always included) from capture events ${s.sourceEventIds}", NOW_VALUE,
    )
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
  private fun dailyCandidates(input: AssembleInput, range: TimeRange?, words: List<String>, latest: Boolean, fallback: Boolean, pool: Pool, trace: MutableList<String>) {
    // 1. structural: every record inside the date range (or last RECENT_FALLBACK_DAYS when the question has no words and no range)
    val structuralRange = range ?: if (fallback) recentRange(input) else null
    if (structuralRange != null) {
      val inRange = TimeWords.utcDays(structuralRange)
        .flatMap { day -> memory.readDay(day).map { MemoryRef(MemoryLevel.DAILY, day, it.id) to it } }
        .filter { (_, r) -> r.atEpochMs >= structuralRange.fromEpochMs && r.atEpochMs < structuralRange.toEpochMs }
        .let { if (fallback) it.sortedByDescending { p -> p.second.atEpochMs } else it.sortedBy { p -> p.second.atEpochMs } }
      // a whole week of "ออกจากบ้าน / ถึงที่ทำงาน" must not crowd out the one-off lines (appointments, new places, notes)
      // ponytail: "repeat" = same text once digits are gone (times, durations); use a line kind from the diary ledger if wording drifts
      val seen = mutableSetOf<String>()
      inRange.forEachIndexed { i, (ref, r) ->
        val repeat = !seen.add(r.text.filterNot { it.isDigit() })
        val outside = !structuralRange.inWindow(r.atEpochMs)
        val v = STRUCTURAL_VALUE - i * 0.001 - (if (repeat) REPEAT_PENALTY else 0.0) - (if (outside) OUTSIDE_WINDOW_PENALTY else 0.0)
        val why = (if (fallback) "recent fallback (${RECENT_FALLBACK_DAYS}d)" else "date word '${structuralRange.word}'") +
          (if (repeat) ", repeats an earlier line" else "") + (if (outside) ", outside the asked part of day" else "")
        pool.get(ref, r, ContextSection.RECALL).add(RetrievalTier.STRUCTURAL, v, why)
      }
      trace += "structural: ${inRange.size} records in range" + (structuralRange.window?.let { ", window $it" } ?: "")
    }
    // 2. word search
    val base = SearchQueryInput("", input.languageCode, limit = limits.maxWordHits, levels = setOf(MemoryLevel.DAILY), fromEpochMs = range?.fromEpochMs, toEpochMs = range?.toEpochMs)
    val hits = wordSearch(words, base, trace).filter { it.memory != null }
    val ordered = if (latest) hits.sortedByDescending { it.memory!!.atEpochMs } else hits
    ordered.forEachIndexed { i, h ->
      // inside a date range the question's own words lift a line above the rest of that range
      val inRange = pool.items[h.memory!!.ref]?.takeIf { RetrievalTier.STRUCTURAL in it.tiers }?.let { it.value + WORD_IN_RANGE_BONUS }
      val v = inRange ?: (50.0 + 10.0 * (1.0 - i.toDouble() / maxOf(1, ordered.size)))
      addHit(h, RetrievalTier.WORDS, v, if (latest) "word match, newest first" else "word match rank ${i + 1}", pool)
    }
    // 3. semantic: semanticCandidates, run by assemble after this so it sees every word/link pick
    // 4. link following from the best word hits
    links?.let { tier ->
      val seeds = ordered.take(limits.maxLinkSeeds).mapNotNull { it.memory?.ref }
      if (seeds.isNotEmpty()) tier.follow(seeds).forEach { addHit(it, RetrievalTier.LINK, 20.0, "linked from a top hit", pool) }
    }
  }

  /**
   * Up to [AssemblerLimits.maxSemanticHits] daily lines the word tiers did not already pick (those only get the tag),
   * valued below every word hit and ranked by similarity. A failure falls back to the word results.
   */
  private fun semanticCandidates(tier: SemanticTier, question: String, range: TimeRange?, pool: Pool, trace: MutableList<String>) {
    val hits = try {
      tier.search(question, limits.maxSemanticHits + pool.items.size)
    } catch (e: Exception) {
      trace += "semantic tier failed (${e::class.simpleName}: ${e.message}); using word results only"
      return
    }
    val daily = hits.filter { h ->
      val m = h.memory ?: return@filter false
      m.ref.level == MemoryLevel.DAILY && (range == null || (m.atEpochMs >= range.fromEpochMs && m.atEpochMs < range.toEpochMs))
    }
    val (known, fresh) = daily.partition { it.memory!!.ref in pool.items }
    known.forEach { pool.items.getValue(it.memory!!.ref).add(RetrievalTier.SEMANTIC, 0.0, "also a semantic match") }
    val added = fresh.take(limits.maxSemanticHits)
    added.forEach { addHit(it, RetrievalTier.SEMANTIC, SEMANTIC_VALUE + SEMANTIC_SPREAD * it.score.coerceIn(0.0, 1.0), "semantic match (cos ${round2(it.score)})", pool) }
    trace += "semantic: ${hits.size} hits, ${added.size} new lines" + (if (range != null) " inside '${range.word}'" else "")
  }

  private fun round2(v: Double) = kotlin.math.round(v * 100) / 100

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

  /** Time phrases are cut out before segmenting: "วันนี้ตอนเช้า" must not search for "นี้" or "ตอน". */
  private fun contentWords(question: String, lang: String): List<String> {
    val rest = TimeWords.all.fold(question) { q, w -> q.replace(w, " ") }
    return pipeline.process(TextInput(rest, lang)).tokens.map { it.text }.filter { it !in STOP }.distinct()
  }

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

  private fun recentRange(input: AssembleInput): TimeRange {
    val offMs = input.utcOffsetMinutes * 60_000L
    val todayStart = (input.nowEpochMs + offMs).floorDiv(DAY_MS) * DAY_MS - offMs
    return TimeRange("recent", todayStart - (RECENT_FALLBACK_DAYS - 1) * DAY_MS, todayStart + DAY_MS, utcOffsetMinutes = input.utcOffsetMinutes)
  }

  private fun fit(input: AssembleInput, standing: List<ContextItem>, others: List<ContextItem>, trace: MutableList<String>, newestFirst: Boolean = false): AssembledContext {
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
    val ordered = kept.sortedWith(
      compareBy<ContextItem> { it.section.ordinal }
        .thenBy { if (it.section != ContextSection.RECALL) 0L else if (newestFirst) -it.atEpochMs else it.atEpochMs },
    )
    return AssembledContext(input.question, input.budgetTokens, ordered, dropped, trace)
  }

  companion object {
    // ponytail: fixed 3-day window for wordless/dateless questions; widen or scan last non-empty days if users ask after a quiet gap
    const val RECENT_FALLBACK_DAYS = 3
    private const val DAY_MS = 86_400_000L
    const val NOW_LABEL = "ตอนนี้"
    val NOW_CUES = listOf("ตอนนี้", "ขณะนี้", "เดี๋ยวนี้", "เมื่อกี้", "เมื่อครู่", "เหลือ", "ถัดไป", "นัดต่อไป", "อยู่ไหน", "อยู่ที่ไหน", "กี่โมงแล้ว", "วันอะไร", "วันที่เท่าไหร่", "วันที่เท่าไร")
    val PAST_CUES = listOf("ไปไหนมาบ้าง", "ไปไหนมา")
    private val LOCATION_NOW_CUES = listOf("อยู่ไหน", "อยู่ที่ไหน")
    /** Id of the "ตอนนี้" item's [MemoryRef]; no memory file holds it (it is rebuilt from capture events every question). */
    const val NOW_ID = "now"
    /** Above every retrieved line, below standing instructions: over budget it goes last but one. */
    private const val NOW_VALUE = 1_000.0
    private val LATEST_CUES = listOf("ล่าสุด", "สุดท้าย")
    private const val STRUCTURAL_VALUE = 100.0
    private const val REPEAT_PENALTY = 20.0
    private const val OUTSIDE_WINDOW_PENALTY = 30.0
    private const val WORD_IN_RANGE_BONUS = 10.0
    /** Semantic lines sit in 30..40: below word hits (50+), above links (20). */
    private const val SEMANTIC_VALUE = 30.0
    private const val SEMANTIC_SPREAD = 10.0

    /** Question filler that never helps retrieval. Grow from real queries. */
    private val STOP = setOf(
      "ไป", "ไหน", "ที่ไหน", "มา", "บ้าง", "เมื่อ", "ไร", "เมื่อไร", "เมื่อไหร่", "เมื่อใด", "คือ", "ครั้ง", "ล่าสุด", "สุดท้าย",
      "ที่", "ได้", "ไหม", "หรือ", "อะไร", "ทำ", "ยัง", "แล้ว", "ของ", "ฉัน", "ผม", "เรา", "ให้", "ช่วย", "บอก", "หน่อย",
      "ครับ", "ค่ะ", "คะ", "นะ", "เป็น", "มี", "จำ", "และ", "กี่", "โมง", "ถึง", "the", "a", "is", "what", "when",
    )
  }
}
