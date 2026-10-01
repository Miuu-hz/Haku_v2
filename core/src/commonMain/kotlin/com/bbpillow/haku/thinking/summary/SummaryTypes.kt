package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.GenerateMetrics

/**
 * [DEMO] = hand-written by the debug demo seeder (com.bbpillow.haku.demo.DemoSeed), checked like a model text.
 * Shown exactly like [MODEL]; never stored as MODEL so a demo paragraph is never mistaken for the model's work.
 */
enum class SummarySource { MODEL, TEMPLATE, DEMO }

/**
 * [lines] = the day's diary line texts as written by DiaryRenderer ("08:30 ออกจากบ้าน" + particle), oldest first.
 * [contextLines] = optional assembled-context text, tone only: it is never a source of facts.
 */
data class EveningSummaryInput(
  val day: String,
  val lines: List<String>,
  val style: DiaryStyle = DiaryStyle(),
  val contextLines: List<String> = emptyList(),
  /** Explicit user request to write again; otherwise a stored summary of the day is returned as is. */
  val regenerate: Boolean = false,
)

data class EveningSummaryOutput(
  val text: String,
  val source: SummarySource,
  /** Null when the model did not run (empty day, stored summary). */
  val metrics: GenerateMetrics?,
  /** Why the model text was refused; empty when [source] is MODEL or the model never ran. */
  val rejectedReasons: List<String>,
  val fromStore: Boolean = false,
)

data class EveningSummaryRecord(
  val day: String,
  val text: String,
  val source: SummarySource,
  val provenance: Provenance = Provenance.INFERRED_FROM_USER_DATA,
)

interface EveningSummaryRepository {
  fun find(day: String): EveningSummaryRecord?
  fun save(record: EveningSummaryRecord)

  /** Drop one day's summary (its source diary line was deleted or edited); the nightly job writes a fresh one. */
  fun delete(day: String): Boolean

  /** Forget everything: drop every stored summary. */
  fun clear()
}

data class SummaryPromptInput(val header: String, val lines: List<String>, val style: DiaryStyle, val contextLines: List<String>)

/** [factLines] = the lines that actually fit the budget; the checker treats only these as facts. */
data class SummaryPromptOutput(val prompt: String, val factLines: List<String>, val estimatedTokens: Int)

data class SummaryCheckInput(val text: String, val factLines: List<String>, val style: DiaryStyle)

data class SummaryCheckOutput(val reasons: List<String>) {
  val ok get() = reasons.isEmpty()
}
