package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.thinking.GenerateEvent
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.GenerateMetrics
import com.bbpillow.haku.thinking.GenerateOutput
import com.bbpillow.haku.thinking.LocalModelClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P2.7. The caller loads the model (the client is already loaded). The day's lines become facts
 * (`เวลา · สถานที่ · ทำอะไร`, [SummaryFacts]) grouped into เช้า / บ่าย / เย็น; each part gets one short model call and its own
 * check, and a part that fails (check, model error, timeout) reads as [SummaryTemplate.section] alone. The parts are
 * joined in code and the user's particle is added once at the end. The joined text is checked again against the whole
 * day, its length limit counting model text only (each part's cap keeps that under [SummaryChecker.MAX_CHARS]; a template
 * part is as long as its lines, like the whole-day template always was). If that fails, or no part is model text, the
 * whole-day [SummaryTemplate.paragraph] is used and not stored.
 * Reasons are returned ("afternoon: invented-place"), never shown; a MODEL text may carry the reasons of its template parts.
 * An empty day is answered with the friendly no-data text and not stored, so a later run can still write the day.
 */
class EveningSummarizer(
  private val client: LocalModelClient,
  private val repository: EveningSummaryRepository,
  private val budgetTokens: Int = SummaryPromptBuilder.DEFAULT_BUDGET_TOKENS,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
  suspend fun summarize(input: EveningSummaryInput): EveningSummaryOutput {
    if (input.lines.isEmpty()) return EveningSummaryOutput(SummaryTemplate.noData(input.style), SummarySource.TEMPLATE, null, emptyList())
    if (!input.regenerate) repository.find(input.day)?.let {
      return EveningSummaryOutput(it.text, it.source, null, emptyList(), fromStore = true)
    }
    val header = ThaiDateFormat.dayHeader(input.day)
    val facts = SummaryFacts.extract(input.lines, input.style.particle)
    val sections = SummaryFacts.sections(facts)
    val cap = partCap(sections.size)
    val parts = sections.map { section(header, it, input.contextLines, cap) }
    val metrics = parts.mapNotNull { it.metrics }.reduceOrNull(::plus)
    val reasons = parts.flatMap { p -> p.reasons.map { "${p.part.name.lowercase()}: $it" } }
    val joined = input.style.particle.appendTo(parts.joinToString(" ") { it.text })
    val templateChars = parts.filterNot { it.model }.sumOf { it.text.length }
    val whole = SummaryChecker.check(
      SummaryCheckInput(joined, listOf(header) + facts.map { it.line }, input.style), maxChars = SummaryChecker.MAX_CHARS + templateChars,
    ).reasons
    val out = if (parts.any { it.model } && whole.isEmpty()) {
      EveningSummaryOutput(joined, SummarySource.MODEL, metrics, reasons)
    } else {
      EveningSummaryOutput(SummaryTemplate.paragraph(input.day, input.lines, input.style), SummarySource.TEMPLATE, metrics, reasons + whole.map { "day: $it" })
    }
    // ponytail: only a model text is stored; the template is free to rebuild and the day may still grow.
    if (out.source == SummarySource.MODEL) repository.save(EveningSummaryRecord(input.day, out.text, out.source))
    return out
  }

  private class Part(val part: SummaryPart, val text: String, val model: Boolean, val metrics: GenerateMetrics?, val reasons: List<String>)

  /** One model call for one part; checked against that part's lines only, with no particle anywhere in it. */
  private suspend fun section(header: String, section: SummarySection, context: List<String>, cap: Int): Part {
    val prompt = SummaryPromptBuilder.build(SummaryPromptInput(section.part, section.facts.map { it.structured }, context), budgetTokens)
    val run = runModel(prompt.prompt)
    val text = run.output?.text?.trim()?.let(::dropEndParticle)
    val lines = listOf(header) + section.facts.take(prompt.factLines.size).map { it.line }
    val reasons = run.failure?.let { listOf(it) }
      ?: SummaryChecker.check(SummaryCheckInput(text.orEmpty(), lines, PLAIN), SECTION_MIN_CHARS, cap).reasons
    val metrics = run.output?.metrics
    return if (reasons.isEmpty() && text != null) Part(section.part, text, true, metrics, emptyList())
    else Part(section.part, SummaryTemplate.section(section), false, metrics, reasons)
  }

  /** The prompt asks for no particle, but a trailing one is harmless to drop: the summary adds the user's once. */
  private fun dropEndParticle(s: String) =
    EndingParticle.entries.filter { it != EndingParticle.NONE }.fold(s.trimEnd(' ', '.')) { acc, p -> acc.removeSuffix(p.text) }.trimEnd()

  private class Run(val output: GenerateOutput?, val failure: String?)

  private suspend fun runModel(prompt: String): Run = try {
    val done = withTimeoutOrNull(timeoutMillis) {
      client.generate(GenerateInput(prompt)).filterIsInstance<GenerateEvent.Finished>().first().output
    }
    when {
      done == null -> Run(null, "timeout") // the cancelled flow already stops the model; an extra cancel() would poison the next run
      done.metrics.cancelled -> Run(done, "cancelled")
      else -> Run(done, null)
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Run(null, "model-error: ${e::class.simpleName}")
  }

  private fun plus(a: GenerateMetrics, b: GenerateMetrics) = GenerateMetrics(
    a.timeToFirstTokenMillis, a.totalMillis + b.totalMillis, a.chunkCount + b.chunkCount, null, a.cancelled || b.cancelled,
  )

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** One part's cap; fewer parts may not go longer, so one sentence stays one sentence. */
    const val SECTION_MAX_CHARS = 180
    private const val JOIN_RESERVE = 10 // joining spaces and the particle

    /** n parts of model text, joined, stay under [SummaryChecker.MAX_CHARS]: 3 parts get 163 each, 1-2 parts 180. */
    fun partCap(parts: Int) = minOf(SECTION_MAX_CHARS, (SummaryChecker.MAX_CHARS - JOIN_RESERVE) / parts.coerceAtLeast(1))
    const val SECTION_MIN_CHARS = 10
    private val PLAIN = DiaryStyle()
  }
}
