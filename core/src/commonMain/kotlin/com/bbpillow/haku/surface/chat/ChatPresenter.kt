package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.index.SearchRepository
import com.bbpillow.haku.thinking.GenerateEvent
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.context.AssembleInput
import com.bbpillow.haku.thinking.context.AssembledContext
import com.bbpillow.haku.thinking.context.ContextAssembler
import com.bbpillow.haku.thinking.context.ContextItem
import com.bbpillow.haku.thinking.context.ContextSection
import com.bbpillow.haku.thinking.context.RetrievalTier
import com.bbpillow.haku.thinking.summary.SummaryCheckInput
import com.bbpillow.haku.thinking.summary.SummaryChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ChatQuestionInput(val question: String, val nowEpochMs: Long, val style: DiaryStyle = DiaryStyle())

/** One source line behind an answer. [day] = local yyyy-MM-dd for a diary line (link to that timeline day), null for a core fact. */
data class ChatCitation(val label: String, val day: String?, val text: String, val atEpochMs: Long, val source: MemoryRef)

/** RETRIEVAL = the cited lines as a template answer (no model); MODEL = the on-device model's words, checked against them. */
enum class ChatAnswerSource { RETRIEVAL, MODEL }

data class ChatAnswerOutput(
  val question: String,
  val text: String,
  val citations: List<ChatCitation>,
  val source: ChatAnswerSource,
  /** Why the model text was refused; empty when the model was not asked or passed. Never shown. */
  val rejectedReasons: List<String> = emptyList(),
)

/** What [ChatPresenter.retrieve] found: the model context, its citations and the no-model answer. */
data class ChatRetrievalOutput(
  val input: ChatQuestionInput,
  val context: AssembledContext,
  val citations: List<ChatCitation>,
  val fallback: ChatAnswerOutput,
)

/**
 * P3.3 ask about your own life. [retrieve] runs on the DB thread (ContextAssembler over memory + SearchRepository);
 * [answer] runs the model off it. Without a model the answer is the retrieved lines themselves ("จากบันทึกของคุณ"),
 * so it works offline. With a model the text must pass [SummaryChecker] against the same lines (no invented times,
 * numbers, places, quotes); otherwise the retrieval answer is shown. Citations are the same either way.
 */
// ponytail: whole answer at once, no token streaming or per-message processing level yet (P3.3 list); add with the chat engine.
class ChatPresenter(
  private val memory: MemoryRepository,
  search: SearchRepository,
  pipeline: TextPipeline,
  private val tzOffsetMinutes: Int,
  synonyms: SynonymTable? = null,
  private val budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
  private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
  private val assembler = ContextAssembler(memory, search, pipeline, synonyms)

  fun retrieve(input: ChatQuestionInput): ChatRetrievalOutput {
    val question = input.question.trim()
    val context = assembler.assemble(AssembleInput(question, budgetTokens, input.nowEpochMs, tzOffsetMinutes))
    val citations = citations(context.items)
    val found = context.items.count { it.section != ContextSection.STANDING } // all retrieved lines, not just the cited few
    return ChatRetrievalOutput(input, context, citations, ChatAnswerOutput(question, retrievalText(citations, found, input.style), citations, ChatAnswerSource.RETRIEVAL))
  }

  /** [client] = an already loaded model, or null (no model file): then the retrieval answer is returned as is. */
  suspend fun answer(retrieval: ChatRetrievalOutput, client: LocalModelClient?): ChatAnswerOutput {
    val fallback = retrieval.fallback
    if (client == null || retrieval.citations.isEmpty()) return fallback
    val text = try {
      withTimeoutOrNull(timeoutMillis) {
        client.generate(GenerateInput(prompt(retrieval))).filterIsInstance<GenerateEvent.Finished>().first().output
      }?.takeIf { !it.metrics.cancelled }?.text?.trim()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return fallback.copy(rejectedReasons = listOf("model-error: ${e::class.simpleName}"))
    } ?: return fallback.copy(rejectedReasons = listOf("timeout"))
    val facts = retrieval.context.items.map { it.text } +
      retrieval.citations.mapNotNull { it.day }.distinct().map(ThaiDateFormat::dayHeader) + retrieval.input.question
    val check = SummaryChecker.check(SummaryCheckInput(text, facts, retrieval.input.style))
    return if (check.ok) fallback.copy(text = text, source = ChatAnswerSource.MODEL) else fallback.copy(rejectedReasons = check.reasons)
  }

  /** Diary lines and core facts (standing instructions are how to answer, not sources). Date questions read in order, others best first. */
  private fun citations(items: List<ContextItem>): List<ChatCitation> {
    val sources = items.filter { it.section != ContextSection.STANDING }
    val ordered = if (sources.any { RetrievalTier.STRUCTURAL in it.tiers }) sources.sortedBy { it.atEpochMs } else sources.sortedByDescending { it.value }
    return ordered.take(MAX_CITATIONS).map { item ->
      val text = memory.find(item.source)?.text ?: item.text.substringAfter("] ")
      if (item.section == ContextSection.RECALL) {
        val day = MemoryFormat.dayOf(item.atEpochMs + tzOffsetMinutes * 60_000L)
        ChatCitation("${ThaiDateFormat.shortWeekday(day)} ${ThaiDateFormat.shortDate(day)}", day, text, item.atEpochMs, item.source)
      } else {
        ChatCitation("ความจำ", null, text, item.atEpochMs, item.source)
      }
    }
  }

  private fun retrievalText(citations: List<ChatCitation>, found: Int, style: DiaryStyle): String {
    if (citations.isEmpty()) return style.particle.appendTo(NOT_FOUND)
    val head = "$FROM_DIARY พบ $found รายการที่เกี่ยวข้อง"
    val newest = citations.filter { it.day != null }.maxByOrNull { it.atEpochMs } ?: return style.particle.appendTo(head)
    val hhmm = MemoryFormat.formatUtc(newest.atEpochMs + tzOffsetMinutes * 60_000L).substring(11, 16)
    return style.particle.appendTo("$head ล่าสุดคือ${ThaiDateFormat.dayHeader(newest.day!!)} เวลา $hhmm")
  }

  private fun prompt(r: ChatRetrievalOutput): String {
    val particle = r.input.style.particle.text
    return buildString {
      appendLine("คุณคือ Haku ตอบคำถามเกี่ยวกับชีวิตของผู้ใช้จากบันทึกด้านล่างเท่านั้น")
      appendLine("ห้ามเพิ่มเวลา ตัวเลข สถานที่ หรือคำพูดที่ไม่มีในบันทึก ถ้าบันทึกไม่พอให้บอกว่าไม่พบ")
      append("ตอบภาษาไทยหนึ่งถึงสองประโยค")
      if (particle.isNotEmpty()) append(" ลงท้ายด้วย \"$particle\"")
      appendLine()
      appendLine("บันทึก:")
      appendLine(r.context.promptText())
      appendLine("คำถาม: ${r.input.question}")
      append("คำตอบ:")
    }
  }

  companion object {
    const val FROM_DIARY = "จากบันทึกของคุณ"
    const val NOT_FOUND = "ยังไม่พบเรื่องนี้ในบันทึกของคุณ ลองถามด้วยชื่อสถานที่หรือวันที่"
    const val MAX_CITATIONS = 5
    const val DEFAULT_BUDGET_TOKENS = 900
    const val DEFAULT_TIMEOUT_MS = 30_000L
  }
}
