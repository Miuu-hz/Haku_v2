package com.bbpillow.haku.platform.signals

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.bbpillow.haku.capture.DeviceSignalKind
import com.bbpillow.haku.platform.capture.CaptureController

/**
 * Charging and unlock (P2.5). These broadcasts cannot be declared in the manifest (Android 8+), so this receiver lives
 * only while the process does. Charging missed while the process was dead is caught up at the next wake-up
 * ([CaptureController.catchUp]); unlocks missed are simply missing.
 * ponytail: unlock coverage is partial; UsageStatsManager (the same special access P2.5 screen-time needs) is the upgrade.
 */
object DeviceSignalReceiver : BroadcastReceiver() {
  private var registered = false

  @Synchronized fun register(context: Context) {
    if (registered) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_POWER_CONNECTED)
      addAction(Intent.ACTION_POWER_DISCONNECTED)
      addAction(Intent.ACTION_USER_PRESENT)
    }
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(this, filter)
    registered = true
  }

  @Synchronized fun unregister(context: Context) {
    if (!registered) return
    context.unregisterReceiver(this)
    registered = false
  }

  override fun onReceive(context: Context, intent: Intent) {
    val now = System.currentTimeMillis()
    CaptureController.runAsync(this, context) { recorder ->
      when (intent.action) {
        Intent.ACTION_POWER_CONNECTED -> recorder.recordCharging(true, now)
        Intent.ACTION_POWER_DISCONNECTED -> recorder.recordCharging(false, now)
        Intent.ACTION_USER_PRESENT -> recorder.recordSignal(DeviceSignalKind.UNLOCK, now)
      }
    }
  }
}
