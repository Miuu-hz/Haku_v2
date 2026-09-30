package com.bbpillow.haku.thinking.summary

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
 * P2.7. Prompt, model, check, then store a model text once per day. The caller loads the model (the client is already loaded).
 * A failed check, a model error or a timeout falls back to [SummaryTemplate]; the reasons are returned, never shown.
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
    val prompt = SummaryPromptBuilder.build(
      SummaryPromptInput(ThaiDateFormat.dayHeader(input.day), input.lines, input.style, input.contextLines),
      budgetTokens,
    )
    val run = runModel(prompt.prompt)
    val checked = run.output?.takeIf { run.failure == null }?.let { SummaryChecker.check(SummaryCheckInput(it.text, prompt.factLines, input.style)) }
    val reasons = run.failure?.let { listOf(it) } ?: checked?.reasons.orEmpty()
    val out = if (checked?.ok == true) {
      EveningSummaryOutput(run.output!!.text.trim(), SummarySource.MODEL, run.output.metrics, emptyList())
    } else {
      EveningSummaryOutput(SummaryTemplate.paragraph(input.day, input.lines, input.style), SummarySource.TEMPLATE, run.output?.metrics, reasons)
    }
    // ponytail: only a model text is stored; the template is free to rebuild and the day may still grow.
    if (out.source == SummarySource.MODEL) repository.save(EveningSummaryRecord(input.day, out.text, out.source))
    return out
  }

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

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
  }
}
