package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.thinking.appointment.AppointmentDraft
import com.bbpillow.haku.thinking.appointment.AppointmentParser

/**
 * P5.4 pre-check for the chat box: appointment sentences like "มีนัดขูดหินปูน 10.00น. พรุ่งนี้"
 * are parsed into an [AppointmentProposal] card. Nothing is written to the device calendar
 * or memory until the user taps add. Callers try this before [IntentCommand] and [ChatPresenter.retrieve].
 */
// ponytail: labels pre-formatted in core so the UI stays purely declarative; parser delegates to AppointmentParser.
class AppointmentCommand(
  private val parser: ((String, Long, Int) -> AppointmentDraft?)? = null,
) {
  fun tryPropose(question: String, nowUtcMs: Long, tzOffsetMinutes: Int): ChatAnswerOutput? {
    val draft = parser?.invoke(question, nowUtcMs, tzOffsetMinutes)
      ?: AppointmentParser.parse(question, nowUtcMs, tzOffsetMinutes)
      ?: return null
    val startLocalMs = draft.startUtcMs + tzOffsetMinutes * 60_000L
    val endLocalMs = draft.endUtcMs + tzOffsetMinutes * 60_000L
    val day = MemoryFormat.dayOf(startLocalMs)
    val dayLabel = "${ThaiDateFormat.shortWeekday(day)} ${ThaiDateFormat.shortDate(day)}"
    val startHhmm = MemoryFormat.formatUtc(startLocalMs).substring(11, 16)
    val endHhmm = MemoryFormat.formatUtc(endLocalMs).substring(11, 16)
    val timeLabel = "$startHhmm–$endHhmm"
    val proposal = AppointmentProposal(
      title = draft.title,
      startUtcMs = draft.startUtcMs,
      endUtcMs = draft.endUtcMs,
      dayLabel = dayLabel,
      timeLabel = timeLabel,
    )
    return ChatAnswerOutput(
      question = question,
      text = PROMPT_TEXT,
      citations = emptyList(),
      source = ChatAnswerSource.RETRIEVAL,
      appointment = proposal,
    )
  }

  companion object {
    const val PROMPT_TEXT = "เพิ่มนัดนี้ลงปฏิทินไหม"
  }
}
