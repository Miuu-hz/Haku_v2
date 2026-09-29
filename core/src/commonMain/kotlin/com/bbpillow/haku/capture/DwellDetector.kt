package com.bbpillow.haku.capture

/** Why the OS woke us (P2.2). Times are UTC epoch ms, already rounded by [CaptureIds.wallTimeMs]. */
sealed interface WakeTrigger {
  val timeUtcMs: Long

  data class Activity(override val timeUtcMs: Long, val activity: ActivityKind, val entering: Boolean) : WakeTrigger

  /** Left the geofence around the open dwell. Authoritative: the stay is over. */
  data class GeofenceExit(override val timeUtcMs: Long) : WakeTrigger
}

/** One wake-up: its triggers, at most one position fix (null = none taken or none available), the stay still open. */
data class DwellDetectInput(val triggers: List<WakeTrigger>, val fix: EventPlace?, val openDwell: CaptureEvent?)

/** Events to append, and the stay open afterwards (drives the geofence: non-null = fence it, null = remove it). */
data class DwellDetectOutput(val events: List<CaptureEvent>, val openDwell: CaptureEvent?)

/**
 * Pure rules turning wake-ups into DwellStart/DwellEnd/ActivityTransition events. No GPS stream: the OS wakes us on an
 * activity change or geofence exit and we look at one fix. Short stops (red lights) still produce a dwell pair; they are
 * dropped once, downstream, by [PlaceThresholds.MIN_DWELL_MS].
 */
object DwellDetector {
  /** Within this of the open dwell = same stay (walk to the coffee machine). Same radius as the merge rule and the geofence. */
  const val SAME_PLACE_RADIUS_M = PlaceThresholds.MERGE_RADIUS_M

  /** Take a fix only when it can change the outcome: arriving somewhere, or moving while a stay is open. */
  fun needsFix(triggers: List<WakeTrigger>, openDwell: CaptureEvent?): Boolean = triggers.any {
    it is WakeTrigger.Activity && it.entering && (it.activity == ActivityKind.STILL || openDwell != null)
  }

  fun detect(input: DwellDetectInput): DwellDetectOutput {
    val out = mutableListOf<CaptureEvent>()
    var open = input.openDwell
    val fix = input.fix
    for (t in input.triggers.sortedBy { it.timeUtcMs }) {
      if (t is WakeTrigger.Activity) out += activityEvent(t)
      val arriving = t is WakeTrigger.Activity && t.entering && t.activity == ActivityKind.STILL
      val moving = t is WakeTrigger.GeofenceExit || (t is WakeTrigger.Activity && t.entering && t.activity != ActivityKind.STILL)
      val current = open
      when {
        arriving && fix != null -> {
          if (current != null && near(current, fix)) continue
          if (current != null && t.timeUtcMs > current.timeUtcMs) out += end(t.timeUtcMs, current)
          open = CaptureEvent(CaptureIds.dwellStart(t.timeUtcMs), t.timeUtcMs, CaptureSource.DwellStart, fix).also(out::add)
        }
        moving && current != null && t.timeUtcMs > current.timeUtcMs && leftPlace(t, current, fix) -> {
          out += end(t.timeUtcMs, current)
          open = null
        }
      }
    }
    return DwellDetectOutput(out, open)
  }

  private fun leftPlace(t: WakeTrigger, open: CaptureEvent, fix: EventPlace?): Boolean = when {
    t is WakeTrigger.GeofenceExit -> true
    fix != null -> !near(open, fix)
    // No fix: only a vehicle is proof of leaving; walking without a fix may be around the same building.
    else -> (t as WakeTrigger.Activity).activity == ActivityKind.IN_VEHICLE
  }

  private fun near(open: CaptureEvent, fix: EventPlace): Boolean {
    val p = open.place ?: return false
    return PlaceRecognizer.distanceMeters(p.lat, p.lon, fix.lat, fix.lon) <= SAME_PLACE_RADIUS_M
  }

  private fun end(timeUtcMs: Long, open: CaptureEvent) =
    CaptureEvent(CaptureIds.dwellEnd(timeUtcMs), timeUtcMs, CaptureSource.DwellEnd, open.place)

  private fun activityEvent(t: WakeTrigger.Activity) = CaptureEvent(
    CaptureIds.activity(t.activity, t.entering, t.timeUtcMs),
    t.timeUtcMs,
    CaptureSource.ActivityTransition(t.activity, t.entering),
  )
}

/**
 * Deterministic ids: the same real-world fact always gets the same id, so a retried broadcast dedupes and a fact
 * the user deleted stays deleted (the store keeps tombstones by id).
 */
object CaptureIds {
  private const val SECOND_MS = 1_000L

  fun dwellStart(timeUtcMs: Long) = "dwell-start-$timeUtcMs"
  fun dwellEnd(timeUtcMs: Long) = "dwell-end-$timeUtcMs"
  fun activity(kind: ActivityKind, entering: Boolean, timeUtcMs: Long) =
    "activity-${kind.name.lowercase()}-${if (entering) "enter" else "exit"}-$timeUtcMs"
  fun device(kind: DeviceSignalKind, timeUtcMs: Long) = "device-${kind.name.lowercase()}-$timeUtcMs"
  fun calendar(eventId: Long, beginUtcMs: Long) = "calendar-$eventId-$beginUtcMs"

  /**
   * Wall time of an event stamped on the monotonic clock (activity transitions carry elapsed-realtime). Rounded to the
   * second: converting the same event twice differs by a few ms, which would otherwise make two ids for one fact.
   */
  fun wallTimeMs(nowWallMs: Long, nowElapsedMs: Long, eventElapsedMs: Long): Long {
    val wall = nowWallMs - (nowElapsedMs - eventElapsedMs)
    return (wall + SECOND_MS / 2).floorDiv(SECOND_MS) * SECOND_MS
  }
}
