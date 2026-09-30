package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance

/** One pill of the date strip: `2026-09-24` shows as `พฤ` over `24`. */
data class TimelineDate(val day: String, val weekday: String, val dayOfMonth: Int)

/** One daily-log line as the timeline shows it. [memoryDay] + [recordId] address the record (UTC file day, D17). */
data class TimelineLine(
  val memoryDay: String,
  val recordId: String,
  val atEpochMs: Long,
  val time: String,
  val text: String,
  val provenance: Provenance,
)

/** Everything the Timeline screen draws. [header] is Buddhist Era, display only. */
data class TimelineUiState(
  val day: String,
  val today: String,
  val header: String,
  val dates: List<TimelineDate>,
  val lines: List<TimelineLine>,
)

/**
 * Maps the daily log to [TimelineUiState] and applies the user's edits (P3.2). Stateless: every intent returns the
 * reloaded state, the screen only holds it. A deleted diary line is tombstoned in the ledger by [DiaryComposer], so
 * composing the day again never brings it back.
 */
class TimelinePresenter(
  private val diary: DiaryComposer,
  private val memory: MemoryRepository,
  private val tzOffsetMinutes: Int,
) {
  fun load(day: String, today: String): TimelineUiState = TimelineUiState(
    day = day,
    today = today,
    header = ThaiDateFormat.dayHeader(day),
    dates = (STRIP_DAYS - 1 downTo 0).map { plusDays(today, -it) }
      .map { TimelineDate(it, ThaiDateFormat.shortWeekday(it), it.takeLast(2).toInt()) },
    lines = diary.readDay(day, tzOffsetMinutes).map { r ->
      val time = MemoryFormat.formatUtc(r.atEpochMs + tzOffsetMinutes * MIN_MS).substring(11, 16)
      TimelineLine(MemoryFormat.dayOf(r.atEpochMs), r.id, r.atEpochMs, time, r.text.removePrefix("$time "), r.provenance)
    },
  )

  // ponytail: last delete only, per presenter instance; a multi-level undo stack when the UI needs one
  private var lastDeleted: Pair<TimelineLine, String?>? = null

  /**
   * Remove the line; a generated diary line is also remembered so it is never written again (asset C3), and counts
   * one strike toward stopping that line type (3 strikes, P3.2). [undoDelete] takes it back.
   */
  fun delete(state: TimelineUiState, line: TimelineLine): TimelineUiState {
    val lineId = diary.lineIdOf(line.memoryDay, line.recordId)
    remove(line)
    lastDeleted = line to lineId
    return load(state.day, state.today)
  }

  /** Bring back the most recent [delete] (snackbar undo): same text and time, strike returned. No-op when nothing to undo. */
  fun undoDelete(state: TimelineUiState): TimelineUiState {
    val (line, lineId) = lastDeleted ?: return state
    lastDeleted = null
    val text = "${line.time} ${line.text}"
    if (lineId == null || !diary.undoDelete(lineId, line.atEpochMs, text, line.provenance)) {
      memory.appendDaily(DailyEventInput(text, line.provenance, line.atEpochMs))
    }
    return load(state.day, state.today)
  }

  /**
   * Replace the line's text. The result is [Provenance.USER_TYPED]: the user wrote it, so it may become permanent
   * memory and the generator must not overwrite it (the original generated line is tombstoned). Blank = no change.
   */
  fun edit(state: TimelineUiState, line: TimelineLine, newText: String): TimelineUiState {
    val text = newText.trim()
    if (text.isEmpty() || text == line.text) return state
    // append before removing, so a crash in between duplicates a line instead of losing the user's text
    memory.appendDaily(DailyEventInput("${line.time} $text", Provenance.USER_TYPED, line.atEpochMs))
    remove(line, countsAsStrike = false) // replaced by the user's own words, not rejected
    return load(state.day, state.today)
  }

  private fun remove(line: TimelineLine, countsAsStrike: Boolean = true) {
    if (!diary.deleteRecord(line.memoryDay, line.recordId, countsAsStrike, tzOffsetMinutes)) memory.deleteDaily(line.memoryDay, line.recordId)
  }

  private fun plusDays(day: String, n: Int): String =
    MemoryFormat.dayOf(requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } + n * DAY_MS)

  private companion object {
    const val STRIP_DAYS = 30 // ponytail: fixed month of pills, add a calendar picker when people scroll further back
    const val MIN_MS = 60_000L
    const val DAY_MS = 86_400_000L
  }
}
