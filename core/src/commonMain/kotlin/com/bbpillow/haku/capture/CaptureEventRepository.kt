package com.bbpillow.haku.capture

private const val DAY_MS = 86_400_000L
private const val UNTITLED_APPOINTMENT = "นัดไม่มีชื่อ"

/** Calendar rows starting in the 24 hours from [dayStartUtcMs], in start order. Deleted ones are gone from the store. */
fun CaptureEventRepository.appointmentsOfDay(dayStartUtcMs: Long): List<CaptureEvent> =
  query(CaptureQueryInput(dayStartUtcMs, dayStartUtcMs + DAY_MS, setOf(CaptureTypes.CALENDAR)))
    .filter { it.source is CaptureSource.CalendarEntry }

/** The title to show for a calendar row. */
fun CaptureEvent.appointmentTitle(): String =
  (source as? CaptureSource.CalendarEntry)?.title?.trim()?.ifEmpty { null } ?: UNTITLED_APPOINTMENT

interface CaptureEventRepository {
  /** False when the id is already stored or the user deleted it earlier (deleted events never come back). */
  fun append(event: CaptureEvent): Boolean

  /** Returns how many were actually written. */
  fun appendAll(events: List<CaptureEvent>): Int

  /** Ordered by time then id. */
  fun query(input: CaptureQueryInput): List<CaptureEvent>

  fun count(): Int

  /** User-initiated delete; remembers the id so it is never written back. */
  fun delete(id: String): Boolean

  /**
   * Calendar re-read: replaces every calendar row whose time is in [[fromUtcMs], [toUtcMs]) with [events], so edits,
   * reschedules and cancellations land. User-deleted ids stay deleted. Returns how many stored rows are new or changed.
   */
  fun replaceCalendar(fromUtcMs: Long, toUtcMs: Long, events: List<CaptureEvent>): Int

  /** Age-based retention. Not a user deletion, so nothing is remembered. Returns rows removed. */
  fun purgeBefore(cutoffUtcMs: Long): Int

  /**
   * "Forget everything": every stored event is deleted AND its id tombstoned, so a calendar re-read or a replayed
   * sensor batch cannot bring it back. Returns rows removed. Earlier tombstones stay (they hold ids only, no content).
   */
  fun forgetAll(): Int
}
