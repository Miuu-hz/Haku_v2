package com.bbpillow.haku.platform.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.bbpillow.haku.capture.ActivityKind
import com.bbpillow.haku.capture.CaptureIds
import com.bbpillow.haku.capture.DwellDetector
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.WakeTrigger
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wake-ups from Play services (activity transitions and the open-stay geofence). Translates, then hands to core. */
class LocationWakeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!CaptureController.isEnabled(context)) return
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val (triggers, geofenceFix) = parse(intent, nowWall, nowElapsed) ?: return
    CaptureController.runAsync(this, context) { recorder ->
      // takeFix frees the capture thread while it waits; this lock keeps wake-ups from interleaving with each other
      wakeOrder.withLock {
        val before = recorder.openDwell(nowWall)
        val fix = geofenceFix ?: if (DwellDetector.needsFix(triggers, before)) CaptureController.takeFix(context) else null
        val out = recorder.onWake(triggers, fix, nowWall)
        Log.i(CaptureController.TAG, "wake $triggers fix=${fix != null} -> ${out.events.map { it.id }}")
        CaptureController.syncGeofence(context, before, out)
        CaptureController.catchUp(context, nowWall)
      }
    }
  }

  private companion object {
    val wakeOrder = Mutex()
  }

  private fun parse(intent: Intent, nowWall: Long, nowElapsed: Long): Pair<List<WakeTrigger>, EventPlace?>? {
    ActivityTransitionResult.extractResult(intent)?.let { result ->
      val triggers = result.transitionEvents.mapNotNull { e ->
        val kind = kindOf(e.activityType) ?: return@mapNotNull null
        val time = CaptureIds.wallTimeMs(nowWall, nowElapsed, e.elapsedRealTimeNanos / 1_000_000)
        WakeTrigger.Activity(time, kind, e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER)
      }
      return triggers to null
    }
    val fence = GeofencingEvent.fromIntent(intent) ?: return null
    if (fence.hasError() || fence.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return null
    val fix = fence.triggeringLocation?.let { EventPlace(it.latitude, it.longitude) }
    return listOf(WakeTrigger.GeofenceExit(CaptureIds.wallTimeMs(nowWall, 0, 0))) to fix
  }

  private fun kindOf(type: Int): ActivityKind? = when (type) {
    DetectedActivity.STILL -> ActivityKind.STILL
    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityKind.WALKING
    DetectedActivity.RUNNING -> ActivityKind.RUNNING
    DetectedActivity.ON_BICYCLE -> ActivityKind.CYCLING
    DetectedActivity.IN_VEHICLE -> ActivityKind.IN_VEHICLE
    else -> null
  }
}
