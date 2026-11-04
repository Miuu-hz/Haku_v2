package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.OpenStay
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognitionOutput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
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
    val (captured, recognized, rendered) = renderDay(input.day, input.tzOffsetMinutes, input.style)
    // persist what was recognised so ids stay stable next run and a later confirmation has a row (existing rows untouched)
    places.addNew(recognized.places)
    removeSupersededDepartures(captured, recognized.visits.map { it.endUtcMs }.toSet(), start, end)

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

  private data class Day(val captured: List<CaptureEvent>, val recognized: PlaceRecognitionOutput, val rendered: DiaryDayOutput)

  private fun renderDay(day: String, tzOffsetMinutes: Int, style: DiaryStyle, known: List<PlaceRecord> = places.list()): Day {
    val (start, end) = window(day, tzOffsetMinutes)
    // one extra day each side: the overnight stay before and the next stay after
    val captured = events.query(CaptureQueryInput(start - DAY_MS, end + DAY_MS))
    val recognized = PlaceRecognizer.recognize(
      PlaceRecognitionInput(PlaceRecognizer.pairDwells(captured), known, tzOffsetMinutes),
    )
    val episodes = EpisodeBuilder.build(DiaryBuildInput(start, end, captured, recognized.places, recognized.visits))
    return Day(captured, recognized, DiaryRenderer.render(day, tzOffsetMinutes, style, episodes))
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

  /**
   * Places got Haku's own names (PlaceNames) since [before] was read: re-word every written line that still reads exactly
   * as [before] rendered it, on every local day that has a daily file. A line the user deleted or replaced by editing has
   * no live ledger row pointing at that text, so it is never touched. Returns how many lines were re-worded.
   * ponytail: re-renders every day (two renders each); fine for months of diary, batch by day range if it gets slow.
   */
  fun relabel(before: List<PlaceRecord>, tzOffsetMinutes: Int, style: DiaryStyle): Int {
    val off = tzOffsetMinutes * 60_000L
    val days = memory.listDays().flatMap { d ->
      val utc = requireNotNull(MemoryFormat.parseUtc("${d}T00:00:00.000Z")) { "bad day: $d" }
      listOf(MemoryFormat.dayOf(utc + off), MemoryFormat.dayOf(utc + DAY_MS - 1 + off))
    }.distinct()
    val now = places.list()
    return days.sumOf { relabelDay(it, tzOffsetMinutes, style, before, now) }
  }

  private fun relabelDay(day: String, tz: Int, style: DiaryStyle, before: List<PlaceRecord>, now: List<PlaceRecord>): Int {
    val old = renderDay(day, tz, style, before).rendered.lines.associateBy { it.id }
    var changed = 0
    for (line in renderDay(day, tz, style, now).rendered.lines) {
      val was = old[line.id]?.takeIf { it.text != line.text } ?: continue
      val entry = ledger.find(line.id)?.takeIf { !it.deleted && it.memoryDay.isNotEmpty() } ?: continue
      val record = memory.readDay(entry.memoryDay).firstOrNull { it.id == entry.memoryRecordId } ?: continue
      if (record.text != was.text) continue
      // new record first, then the ledger, then the old one: a crash leaves a duplicate the next run's twin check reuses, never a lost line
      val memoryDay = MemoryFormat.dayOf(record.atEpochMs)
      val fresh = memory.readDay(memoryDay).firstOrNull { it.atEpochMs == record.atEpochMs && it.text == line.text && it.provenance == record.provenance }
        ?: memory.appendDaily(DailyEventInput(line.text, record.provenance, record.atEpochMs))
      ledger.relink(line.id, memoryDay, fresh.id)
      memory.deleteDaily(entry.memoryDay, record.id)
      changed++
    }
    return changed
  }

  /** The stays and places the diary sees for the local day (timeline mini map, P3.2). Read-only; deleted events are gone. */
  fun placesOfDay(day: String, tzOffsetMinutes: Int): PlaceRecognitionOutput =
    renderDay(day, tzOffsetMinutes, DiaryStyle()).recognized

  /** The stay still open at the end of the local [day]: arrived, no departure yet. Null when the user was moving or nothing is known. */
  fun openStay(day: String, tzOffsetMinutes: Int): OpenStay? {
    val (_, end) = window(day, tzOffsetMinutes)
    val from = end - OPEN_STAY_LOOKBACK_DAYS * DAY_MS
    val captured = events.query(CaptureQueryInput(from, end))
    val open = PlaceRecognizer.openDwell(captured) ?: return null
    val place = open.place ?: return null
    return OpenStay(open.timeUtcMs, place.lat, place.lon)
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

  /**
   * Same, from the daily-log record the timeline shows. False when the record is not a diary line. When the ledger row
   * is missing (crash between append and recordWritten) and [tzOffsetMinutes] is given, the line id is recovered by
   * re-rendering that day and matching the record, so the tombstone still lands.
   */
  fun deleteRecord(memoryDay: String, recordId: String, countsAsStrike: Boolean = true, tzOffsetMinutes: Int? = null): Boolean {
    ledger.findByRecord(memoryDay, recordId)?.let { return deleteLine(it.lineId, countsAsStrike) }
    val record = memory.readDay(memoryDay).firstOrNull { it.id == recordId }
    if (record == null || tzOffsetMinutes == null || record.provenance != Provenance.INFERRED_FROM_USER_DATA) return false
    val localDay = MemoryFormat.dayOf(record.atEpochMs + tzOffsetMinutes * 60_000L)
    val line = renderDay(localDay, tzOffsetMinutes, DiaryStyle()).rendered.lines
      .firstOrNull { it.timeUtcMs == record.atEpochMs && record.text.startsWith(it.text) } ?: return false
    if (ledger.find(line.id)?.deleted == true) return false
    memory.deleteDaily(memoryDay, recordId)
    ledger.markDeleted(line.id, countsAsStrike)
    return true
  }

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
    const val OPEN_STAY_LOOKBACK_DAYS = 30
    const val DAY_MS = 86_400_000L
  }
}
