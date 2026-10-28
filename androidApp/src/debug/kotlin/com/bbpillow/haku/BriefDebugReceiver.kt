package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bbpillow.haku.platform.notify.BriefWorker
import com.bbpillow.haku.surface.settings.BriefKind
import kotlinx.coroutines.launch

/**
 * P5.2 test hook: builds and posts the daily brief now, ignoring the toggle, the time and the once-a-day guard
 * (nothing to say still posts nothing). Logcat tag `HakuBrief`:
 * `adb shell am broadcast -n com.bbpillow.haku/.BriefDebugReceiver -a com.bbpillow.haku.debug.BRIEF --es kind morning|evening`
 */
class BriefDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val kind = if (intent.getStringExtra("kind").equals("evening", ignoreCase = true)) BriefKind.EVENING else BriefKind.MORNING
    val app = context.applicationContext as HakuApplication
    val pending = goAsync()
    app.graph.scope.launch {
      try {
        Log.i(TAG, "$kind ${BriefWorker.postOncePerDay(app, kind, force = true)}")
      } catch (t: Throwable) {
        Log.e(TAG, "brief failed", t)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    const val ACTION = "com.bbpillow.haku.debug.BRIEF"
    const val TAG = "HakuBrief"
  }
}
