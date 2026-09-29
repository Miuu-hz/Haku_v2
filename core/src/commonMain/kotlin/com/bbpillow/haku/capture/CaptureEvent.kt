package com.bbpillow.haku.capture

/** A coordinate the device reported. Never leaves the device raw; see P2.3 (one-time area-name lookup). */
data class EventPlace(val lat: Double, val lon: Double, val placeId: String? = null)

enum class ActivityKind { STILL, WALKING, RUNNING, CYCLING, IN_VEHICLE }

/** Coarse device signals only (P2.5): no per-app usage is ever recorded. */
enum class DeviceSignalKind { CHARGING_START, CHARGING_END, UNLOCK, IDLE_START, IDLE_END }

/** Where an event came from. Typed so the diary (P2.6) can pick a template per kind. */
sealed interface CaptureSource {
  data object DwellStart : CaptureSource
  data object DwellEnd : CaptureSource
  data class ActivityTransition(val activity: ActivityKind, val entering: Boolean) : CaptureSource
  data class DeviceSignal(val signal: DeviceSignalKind) : CaptureSource
  data class CalendarEntry(val title: String) : CaptureSource
}

/**
 * One raw fact. [id] is supplied by the producer and must be stable for the same real-world fact
 * (e.g. calendar instance id, "dwell-start-<ms>"), because that is how a deleted event is recognised
 * if a producer re-reads it later. Times are UTC epoch ms (D17); [endUtcMs] only for spans (calendar).
 */
data class CaptureEvent(
  val id: String,
  val timeUtcMs: Long,
  val source: CaptureSource,
  val place: EventPlace? = null,
  val endUtcMs: Long? = null,
  val confidence: Double = 1.0,
)

/** Query filter; [fromUtcMs] inclusive, [toUtcMs] exclusive. [types] are [CaptureTypes] keys, null = all. */
data class CaptureQueryInput(val fromUtcMs: Long, val toUtcMs: Long, val types: Set<String>? = null)

/** Source-type keys used in storage and in [CaptureQueryInput.types]. */
object CaptureTypes {
  const val DWELL_START = "dwell_start"
  const val DWELL_END = "dwell_end"
  const val ACTIVITY = "activity"
  const val DEVICE = "device"
  const val CALENDAR = "calendar"
}
