package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance

/**
 * Capture events, then episodes, then Thai lines, then the daily log. Idempotent: a line already written, or deleted
 * by the user, is never written again (the [DiaryLedgerRepository] decides). No model involved (P2.6); P2.7 polishes it.
 *
 * ponytail: the daily file is named by the UTC day of the line's time (MemoryStore, D17), so a Bangkok local day spans
 * two files; [readDay] hides that. A stay still in progress has no end event yet and is written once it ends.
 */
class DiaryComposer(
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val memory: MemoryRepository,
  private val ledger: DiaryLedgerRepository,
) {
  fun compose(input: DiaryComposeInput): DiaryComposeOutput {
    val (start, end) = window(input.day, input.tzOffsetMinutes)
    // one extra day each side: the overnight stay before and the next stay after
    val captured = events.query(CaptureQueryInput(start - DAY_MS, end + DAY_MS))
    val recognized = PlaceRecognizer.recognize(
      PlaceRecognitionInput(PlaceRecognizer.pairDwells(captured), places.list(), input.tzOffsetMinutes),
    )
    val episodes = EpisodeBuilder.build(DiaryBuildInput(start, end, captured, recognized.places, recognized.visits))
    val rendered = DiaryRenderer.render(input.day, input.tzOffsetMinutes, input.style, episodes)

    val written = mutableListOf<DiaryLineRecord>()
    var existing = 0
    var suppressed = 0
    for (line in rendered.lines) {
      val known = ledger.find(line.id)
      when {
        known?.deleted == true -> suppressed++
        known == null && ledger.isSuppressed(line.typeKey) -> suppressed++ // 3 strikes (P3.2): no tombstone, so un-suppress brings the type back
        known != null -> existing++
        write(line) -> written += line
        else -> existing++
      }
    }
    return DiaryComposeOutput(rendered.header, written, existing, suppressed)
  }

  /** The local day's records from the daily log, oldest first. */
  fun readDay(day: String, tzOffsetMinutes: Int): List<MemoryRecord> {
    val (start, end) = window(day, tzOffsetMinutes)
    return listOf(MemoryFormat.dayOf(start), MemoryFormat.dayOf(end - 1)).distinct()
      .flatMap(memory::readDay)
      .filter { it.atEpochMs in start until end }
      .sortedBy { it.atEpochMs }
  }

  /** User deleted a line: remove it from the daily log and remember it so it is never regenerated. */
  fun deleteLine(lineId: String, countsAsStrike: Boolean = true): Boolean {
    val entry = ledger.find(lineId)
    if (entry?.deleted == true) return false
    if (entry != null) memory.deleteDaily(entry.memoryDay, entry.memoryRecordId)
    ledger.markDeleted(lineId, countsAsStrike)
    return entry != null
  }

  /** Same, from the daily-log record the timeline shows. False when the record is not a diary line. */
  fun deleteRecord(memoryDay: String, recordId: String, countsAsStrike: Boolean = true): Boolean =
    ledger.findByRecord(memoryDay, recordId)?.let { deleteLine(it.lineId, countsAsStrike) } ?: false

  /** The ledger line id behind a daily-log record; null when the record is not a live diary line. */
  fun lineIdOf(memoryDay: String, recordId: String): String? =
    ledger.findByRecord(memoryDay, recordId)?.takeIf { !it.deleted }?.lineId

  /**
   * Undo [deleteLine] within the session (the caller still has the record's time and text): re-appends the record,
   * clears the tombstone, returns the strike. False when the line was not deleted.
   */
  fun undoDelete(lineId: String, atEpochMs: Long, text: String, provenance: Provenance): Boolean {
    if (ledger.find(lineId)?.deleted != true) return false
    val record = memory.appendDaily(DailyEventInput(text, provenance, atEpochMs))
    return ledger.restore(lineId, MemoryFormat.dayOf(atEpochMs), record.id)
  }

  /** Settings: line types the diary has stopped writing, and the way back. */
  fun suppressedTypes(): List<SuppressedLineType> = ledger.suppressedTypes()

  fun unsuppress(typeKey: String) = ledger.unsuppress(typeKey)

  /** True when newly appended; false when an identical record was already there (ledger write lost in a crash). */
  private fun write(line: DiaryLineRecord): Boolean {
    val memoryDay = MemoryFormat.dayOf(line.timeUtcMs)
    val twin = memory.readDay(memoryDay).firstOrNull {
      it.atEpochMs == line.timeUtcMs && it.text == line.text && it.provenance == Provenance.INFERRED_FROM_USER_DATA
    }
    val record = twin ?: memory.appendDaily(DailyEventInput(line.text, line.provenance, line.timeUtcMs))
    ledger.recordWritten(line.id, memoryDay, record.id, line.typeKey)
    return twin == null
  }

  private fun window(day: String, tzOffsetMinutes: Int): Pair<Long, Long> {
    val utcMidnight = requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" }
    val start = utcMidnight - tzOffsetMinutes * 60_000L
    return start to start + DAY_MS
  }

  private companion object {
    const val DAY_MS = 86_400_000L
  }
}
