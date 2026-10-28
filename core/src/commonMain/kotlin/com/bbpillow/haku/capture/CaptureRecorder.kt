package com.bbpillow.haku.capture

/** One calendar instance as the platform read it (Android: CalendarContract.Instances). */
data class CalendarRowInput(
  val eventId: Long,
  val beginUtcMs: Long,
  val endUtcMs: Long,
  val title: String?,
  val allDay: Boolean = false,
  val canceled: Boolean = false,
  val declined: Boolean = false,
)

/**
 * The one place platform adapters (P2.2 location, P2.5 signals/calendar) write capture events through. Keeps the
 * Android side a thin translator: it reads the OS, this decides what is worth storing. Not thread-safe (as the store).
 *
 * [onNewEvents] hears the stay and calendar rows that are new (or retitled) in the store, never re-reads or user-deleted
 * ids; P5.3 intentions fire from it. Called on the caller's thread after the write. Device signals are not passed on.
 */
class CaptureRecorder(
  private val events: CaptureEventRepository,
  private val onNewEvents: (List<CaptureEvent>) -> Unit = {},
) {
  /** The stay still open: the latest dwell event within [LOOKBACK_MS], if it is a start. The store is the only state. */
  fun openDwell(nowUtcMs: Long): CaptureEvent? =
    events.query(CaptureQueryInput(nowUtcMs - LOOKBACK_MS, nowUtcMs + 1, setOf(CaptureTypes.DWELL_START, CaptureTypes.DWELL_END)))
      .lastOrNull()
      ?.takeIf { it.source == CaptureSource.DwellStart }

  /** Runs [DwellDetector] and stores what it produced. [output.events] lists all produced, including ones already stored. */
  fun onWake(triggers: List<WakeTrigger>, fix: EventPlace?, nowUtcMs: Long): DwellDetectOutput {
    val output = DwellDetector.detect(DwellDetectInput(triggers, fix, openDwell(nowUtcMs)))
    output.events.filter(events::append).takeIf { it.isNotEmpty() }?.let(onNewEvents)
    return output
  }

  fun recordSignal(kind: DeviceSignalKind, timeUtcMs: Long, confidence: Double = 1.0): Boolean =
    events.append(CaptureEvent(CaptureIds.device(kind, timeUtcMs), timeUtcMs, CaptureSource.DeviceSignal(kind), confidence = confidence))

  /**
   * Records a charging edge only when the state differs from the last one stored, so the live broadcast and the
   * catch-up check at each wake-up (process was dead when the cable went in) never double count.
   */
  fun recordCharging(isCharging: Boolean, timeUtcMs: Long, confidence: Double = 1.0): Boolean {
    val kinds = setOf(DeviceSignalKind.CHARGING_START, DeviceSignalKind.CHARGING_END)
    val last = events.query(CaptureQueryInput(timeUtcMs - LOOKBACK_MS, timeUtcMs + 1, setOf(CaptureTypes.DEVICE)))
      .lastOrNull { (it.source as CaptureSource.DeviceSignal).signal in kinds }
    val kind = if (isCharging) DeviceSignalKind.CHARGING_START else DeviceSignalKind.CHARGING_END
    if (last != null && (last.source as CaptureSource.DeviceSignal).signal == kind) return false
    // First ever reading of "not charging" says nothing happened; only a start is news.
    if (last == null && !isCharging) return false
    return recordSignal(kind, timeUtcMs, confidence)
  }

  /**
   * Stores appointments and returns how many are new or changed. All-day, cancelled, declined and untitled entries are
   * skipped. Rows stored inside the read window [[windowFromUtcMs], [windowToUtcMs]) are replaced, so a reschedule,
   * a retitle or a cancellation in the calendar shows up here; pass the window the platform queried.
   * ponytail: without a window only the span of the given rows is replaced, so a moved event is not caught then.
   */
  fun recordCalendar(rows: List<CalendarRowInput>, windowFromUtcMs: Long? = null, windowToUtcMs: Long? = null): Int {
    val from = windowFromUtcMs ?: rows.minOfOrNull { it.beginUtcMs } ?: return 0
    val to = windowToUtcMs ?: (rows.maxOf { it.beginUtcMs } + 1)
    val real = rows.filter { !it.allDay && !it.canceled && !it.declined && !it.title.isNullOrBlank() }.map {
      CaptureEvent(CaptureIds.calendar(it.eventId, it.beginUtcMs), it.beginUtcMs, CaptureSource.CalendarEntry(it.title!!.trim()), endUtcMs = it.endUtcMs)
    }
    val window = CaptureQueryInput(from, to, setOf(CaptureTypes.CALENDAR))
    val before = events.query(window).associate { it.id to it.source }
    val changed = events.replaceCalendar(from, to, real)
    if (changed > 0) events.query(window).filter { before[it.id] != it.source }.takeIf { it.isNotEmpty() }?.let(onNewEvents)
    return changed
  }

  companion object {
    // ponytail: a stay or charge older than a week is treated as closed/unknown; widen if phones sit untouched longer.
    const val LOOKBACK_MS = 7 * 86_400_000L
  }
}
