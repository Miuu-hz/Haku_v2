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
    val (dayLabel, timeLabel) = formatLabels(startLocalMs, endLocalMs, draft.allDay)
    val proposal = AppointmentProposal(
      title = draft.title,
      startUtcMs = draft.startUtcMs,
      endUtcMs = draft.endUtcMs,
      dayLabel = dayLabel,
      timeLabel = timeLabel,
      allDay = draft.allDay,
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

    fun formatLabels(startLocalMs: Long, endLocalMs: Long, allDay: Boolean): Pair<String, String> {
      val startDay = MemoryFormat.dayOf(startLocalMs)
      return if (allDay) {
        val lastDay = MemoryFormat.dayOf(endLocalMs - 1L)
        val dayLabel = if (startDay == lastDay) {
          "${ThaiDateFormat.shortWeekday(startDay)} ${ThaiDateFormat.shortDate(startDay)}"
        } else {
          val (sY, sM, sD) = startDay.split('-').map { it.toInt() }
          val (lY, lM, lD) = lastDay.split('-').map { it.toInt() }
          if (sY == lY && sM == lM) {
            "$sD–$lD ${ThaiDateFormat.shortDate(startDay).substringAfter(' ')}"
          } else {
            "${ThaiDateFormat.shortDate(startDay)}–${ThaiDateFormat.shortDate(lastDay)}"
          }
        }
        dayLabel to "ทั้งวัน"
      } else {
        val dayLabel = "${ThaiDateFormat.shortWeekday(startDay)} ${ThaiDateFormat.shortDate(startDay)}"
        val startHhmm = MemoryFormat.formatUtc(startLocalMs).substring(11, 16)
        val endHhmm = MemoryFormat.formatUtc(endLocalMs).substring(11, 16)
        dayLabel to "$startHhmm–$endHhmm"
      }
    }
  }
}
