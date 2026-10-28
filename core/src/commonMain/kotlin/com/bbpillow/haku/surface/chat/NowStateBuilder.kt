package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.CaptureTypes
import com.bbpillow.haku.capture.DwellRecord
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.DiaryTemplates
import com.bbpillow.haku.capture.diary.PlaceRef
import com.bbpillow.haku.thinking.context.NowAppointmentRecord
import com.bbpillow.haku.thinking.context.NowStateInput

/**
 * P1.9 "ตอนนี้" from the capture stores: the open stay (newest dwell event is a start with a position), today's calendar
 * rows not ended yet, and the last stay left today (same recognizer as the diary, so it matches the "ออกจาก…" lines).
 * Names follow the diary rule: confirmed name, else "สถานที่หนึ่ง". Run on the DB thread.
 * ponytail: only stays opened since yesterday 00:00 local count as "now"; an older open start (capture off) reads as unknown.
 */
class NowStateBuilder(
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val tzOffsetMinutes: Int,
) {
  fun build(nowUtcMs: Long): NowStateInput {
    val offMs = tzOffsetMinutes * MIN_MS
    val dayStart = (nowUtcMs + offMs).floorDiv(DAY_MS) * DAY_MS - offMs
    val known = places.list()
    val recent = events.query(CaptureQueryInput(dayStart - DAY_MS, nowUtcMs + 1))
    val ids = mutableListOf<String>()

    val open = recent.lastOrNull { it.source == CaptureSource.DwellStart || it.source == CaptureSource.DwellEnd }
      ?.takeIf { it.source == CaptureSource.DwellStart && it.place != null }
    val here = open?.let { s ->
      ids += s.id
      val stay = DwellRecord(s.timeUtcMs, nowUtcMs, s.place!!.lat, s.place.lon)
      nameOf(PlaceRecognizer.assignPlaces(listOf(stay), known).second.single().placeId, known)
    }

    val left = PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(recent), known, tzOffsetMinutes))
      .visits.filter { it.endUtcMs in dayStart..nowUtcMs }.maxByOrNull { it.endUtcMs }
    left?.let { v -> recent.firstOrNull { it.source == CaptureSource.DwellEnd && it.timeUtcMs == v.endUtcMs }?.let { ids += it.id } }

    val today = events.query(CaptureQueryInput(dayStart, dayStart + DAY_MS, setOf(CaptureTypes.CALENDAR)))
      .filter { it.source is CaptureSource.CalendarEntry }
    val remaining = today.filter { (it.endUtcMs ?: it.timeUtcMs) > nowUtcMs }.take(MAX_REMAINING)
    ids += remaining.map { it.id }

    return NowStateInput(
      placeName = here,
      sinceUtcMs = open?.timeUtcMs,
      remaining = remaining.map { e ->
        NowAppointmentRecord((e.source as CaptureSource.CalendarEntry).title.trim().ifEmpty { UNTITLED }, e.timeUtcMs, e.endUtcMs)
      },
      hasAppointmentsToday = today.isNotEmpty(),
      lastLeftPlace = left?.let { nameOf(it.placeId, known) },
      lastLeftUtcMs = left?.endUtcMs,
      sourceEventIds = ids,
    )
  }

  private fun nameOf(placeId: String, known: List<PlaceRecord>): String =
    DiaryTemplates.place(known.firstOrNull { it.id == placeId && it.confirmed }?.let { PlaceRef(it.label, it.category, it.id) } ?: PlaceRef())

  private companion object {
    const val MAX_REMAINING = 3
    const val UNTITLED = "นัดไม่มีชื่อ"
    const val MIN_MS = 60_000L
    const val DAY_MS = 86_400_000L
  }
}
