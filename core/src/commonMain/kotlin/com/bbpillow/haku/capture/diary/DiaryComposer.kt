package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
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
    removeSupersededDepartures(captured, recognized.visits.map { it.endUtcMs }.toSet(), start, end)

    val written = mutableListOf<DiaryLineRecord>()
    var existing = 0
    var suppressed = 0
    for (line in rendered.lines) {
      val known = ledger.find(line.id)
      when {
        known?.deleted == true -> suppressed++
        known != null -> existing++
        write(line) -> written += line
        else -> existing++
      }
    }
    return DiaryComposeOutput(rendered.header, written, existing, suppressed)
  }

  /**
   * A stay that grew (the user came back within the merge gap) leaves a written "ออกจาก" line whose end event no longer
   * ends a stay. That line is stale, not deleted by the user: drop it from the log without a tombstone.
   */
  private fun removeSupersededDepartures(captured: List<CaptureEvent>, visitEnds: Set<Long>, from: Long, to: Long) {
    captured.filter { it.source == CaptureSource.DwellEnd && it.timeUtcMs in from until to && it.timeUtcMs !in visitEnds }
      .groupBy({ it.timeUtcMs }, { it.id })
      .forEach { (time, ids) ->
        val entry = ledger.find(DiaryLineIds.of(DiaryLineKind.DEPART, ids, time)) ?: return@forEach
        if (!entry.deleted && entry.memoryDay.isNotEmpty()) memory.deleteDaily(entry.memoryDay, entry.memoryRecordId)
      }
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
  fun deleteLine(lineId: String): Boolean {
    val entry = ledger.find(lineId)
    if (entry?.deleted == true) return false
    if (entry != null) memory.deleteDaily(entry.memoryDay, entry.memoryRecordId)
    ledger.markDeleted(lineId)
    return entry != null
  }

  /** Same, from the daily-log record the timeline shows. False when the record is not a diary line. */
  fun deleteRecord(memoryDay: String, recordId: String): Boolean =
    ledger.findByRecord(memoryDay, recordId)?.let { deleteLine(it.lineId) } ?: false

  /** True when newly appended; false when an identical record was already there (ledger write lost in a crash). */
  private fun write(line: DiaryLineRecord): Boolean {
    val memoryDay = MemoryFormat.dayOf(line.timeUtcMs)
    val twin = memory.readDay(memoryDay).firstOrNull {
      it.atEpochMs == line.timeUtcMs && it.text == line.text && it.provenance == Provenance.INFERRED_FROM_USER_DATA
    }
    val record = twin ?: memory.appendDaily(DailyEventInput(line.text, line.provenance, line.timeUtcMs))
    ledger.recordWritten(line.id, memoryDay, record.id)
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
