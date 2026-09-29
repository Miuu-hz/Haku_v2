package com.bbpillow.haku.platform.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Activity-transition and geofence registrations do not survive a reboot or an app update; put them back if capture is on. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
    if (relevant && CaptureController.isEnabled(context)) CaptureController.start(context)
  }
}
