package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.PromotionTarget
import com.bbpillow.haku.memory.ProvenanceNotAllowedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Nightly memory consolidation (P1.8, D4). The nightly job calls [dream] once per night.
 * Stage 1 tidy duplicates, stage 2 find recurring themes (rules only), stage 3 promote those that pass the hard gate
 * through [MemoryRepository.promote], the only way into long-term memory. The optional [phraser] only words a fact
 * that rules already chose; its output is checked and falls back to a template.
 */
class DreamEngine(
  private val memory: MemoryRepository,
  pipeline: TextPipeline,
  private val config: DreamConfig = DreamConfig(),
  private val phraser: FactPhraser? = null,
) {
  private val themes = DreamThemes(pipeline, config.languageCode)
  private val ledger = DreamLedger(memory)

  /** User rejected a theme (key from the dream report data): it is never promoted. */
  fun rejectTheme(themeKey: String) = ledger.reject(themeKey)

  /** Idempotent per night: a second call for the same [DreamInput.day] changes nothing and returns the stored report. */
  suspend fun dream(input: DreamInput): DreamOutput {
    val today = dayIndex(input.day)
    ledger.syncForgotten() // always first, so a fact the user deleted is never re-promoted
    memory.readDreamReport(input.day)?.let { return DreamOutput(input.day, true, emptyList(), false, emptyList(), emptyList(), false, it) }

    val (loaded, truncated) = load(today)
    val (tidied, cancelled) = tidy(loaded)
    val gone = tidied.map { it.id }.toSet()
    val remaining = loaded.filter { it.record.id !in gone }

    val (promoted, held) = promote(themes.find(remaining), today)
    val out = DreamOutput(input.day, false, tidied, cancelled, promoted, held, truncated, "")
    val report = DreamReport.render(out, config.minDays)
    memory.writeDreamReport(input.day, report)
    return out.copy(report = report)
  }

  private fun dayIndex(day: String): Long =
    requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } / DAY_MS

  /** Newest days first, at most [DreamConfig.maxRecords] records (work bound for the charging window). */
  private fun load(today: Long): Pair<List<DayRecord>, Boolean> {
    val days = memory.listDays().filter { dayIndex(it) in (today - config.lookbackDays + 1)..today }.sortedDescending()
    val out = mutableListOf<DayRecord>()
    for (d in days) {
      for (r in memory.readDay(d)) {
        if (out.size >= config.maxRecords) return out to true
        out += DayRecord(d, r)
      }
    }
    return out to false
  }

  private fun tidy(loaded: List<DayRecord>): Pair<List<TidiedRecord>, Boolean> {
    val daily = loaded.groupBy({ it.day }, { it.record }).flatMap { (d, rs) -> tidyGroup(MemoryLevel.DAILY, d, rs, themes::normalize) }
    val intentions = memory.readIntentions()
    val found = daily + tidyGroup(MemoryLevel.INTENTION, "", intentions, themes::normalize)
    val scanned = loaded.size + intentions.size
    if (found.isEmpty()) return emptyList<TidiedRecord>() to false
    if (scanned >= MIN_SCAN_FOR_LOSS_CHECK && found.size > scanned * config.maxTidyLossRatio) return emptyList<TidiedRecord>() to true
    for (t in found) if (t.level == MemoryLevel.DAILY) memory.deleteDaily(t.day, t.id) else memory.deleteIntention(t.id)
    return found to false
  }

  private suspend fun promote(found: List<Theme>, today: Long): Pair<List<PromotedFact>, List<HeldBackTheme>> {
    val promoted = mutableListOf<PromotedFact>()
    val held = mutableListOf<HeldBackTheme>()
    val known = memory.listCategories().flatMap { memory.readCategory(it.slug) }.map { themes.normalize(it.text) }.toMutableSet()
    var coreSize = memory.listCategories().sumOf { it.factCount }
    val scored = found.map { it to themes.score(it, today, config, ::dayIndex) }.sortedWith(compareByDescending<Pair<Theme, Double>> { it.second }.thenBy { it.first.key })
    for ((t, score) in scored) {
      fun hold(reason: HoldReason) {
        if (reason == HoldReason.FORGOTTEN || reason == HoldReason.REJECTED || t.days >= config.minReportDays) {
          held += HeldBackTheme(t.subject, reason, t.days, t.records.map { it.record.id })
        }
      }
      val reason = when {
        ledger.isForgotten(t.key) -> HoldReason.FORGOTTEN
        ledger.isRejected(t.key) -> HoldReason.REJECTED
        ledger.isPromoted(t.key) -> continue // already in core, nothing to say
        t.eligibleDays < config.minDays -> if (t.days >= config.minDays) HoldReason.PROVENANCE else HoldReason.TOO_FEW_DAYS
        themes.normalize(themes.template(t)) in known -> HoldReason.ALREADY_KNOWN
        score < config.minScore -> HoldReason.LOW_SCORE
        promoted.size >= config.maxPromotionsPerRun || coreSize >= config.maxCoreFacts -> HoldReason.BUDGET
        else -> null
      }
      if (reason != null) { hold(reason); continue }
      val fact = promoteOne(t, score)
      if (fact == null) { hold(HoldReason.SOURCE_MISSING); continue }
      if (fact == PROVENANCE_REFUSED) { hold(HoldReason.PROVENANCE); continue }
      promoted += fact
      known += themes.normalize(fact.text)
      coreSize++
    }
    return promoted to held
  }

  private suspend fun promoteOne(t: Theme, score: Double): PromotedFact? {
    val source = t.eligible.maxBy { it.record.atEpochMs }
    val category = themes.category(t)
    val record = try {
      memory.promote(MemoryRef(MemoryLevel.DAILY, source.day, source.record.id), PromotionTarget.Core(category))
    } catch (e: ProvenanceNotAllowedException) {
      return PROVENANCE_REFUSED
    } ?: return null
    ledger.recordPromoted(t.key, category, record.id) // before the rewrite: a crash later still can never double-promote
    val text = phrase(t)
    val final = if (text == record.text) record else memory.updateCoreFact(category, record.id, text) ?: record
    return PromotedFact(category, record.id, final.text, t.key, score, t.eligibleDays, t.eligible.map { it.record.id })
  }

  private suspend fun phrase(t: Theme): String {
    val template = themes.template(t)
    if (t.kind == ThemeKind.NOTE || phraser == null) return template
    val sources = t.eligible.map { it.record.text }
    val model = try {
      phraser.phrase(PhraseInput(t.kind, t.subject, t.eligibleDays, sources))?.trim()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
    val ok = model != null && DreamFactChecker.check(model, t.subject, sources, t.eligibleDays, config.minDays).isEmpty()
    return if (ok) model!! else template
  }

  private companion object {
    const val DAY_MS = 86_400_000L
    const val MIN_SCAN_FOR_LOSS_CHECK = 10
    val PROVENANCE_REFUSED = PromotedFact("", "", "", "", 0.0, 0, emptyList())
  }
}
