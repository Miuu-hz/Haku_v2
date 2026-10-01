package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.platform.NightlyWorker
import com.bbpillow.haku.platform.capture.CaptureController
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-only capture switch (onboarding starts capture in the real app) (docs/permissions.md). Output goes to logcat tag HakuCapture.
 * adb shell am broadcast -n com.bbpillow.haku/.CaptureDebugReceiver -a com.bbpillow.haku.debug.START
 * ... -a com.bbpillow.haku.debug.STOP
 * ... -a com.bbpillow.haku.debug.DUMP  --es day 2026-10-01   (captured events of that local day; default today)
 * ... -a com.bbpillow.haku.debug.DIARY --es day 2026-10-01   (compose the rule-based diary for that day and print it)
 * ... -a com.bbpillow.haku.debug.NIGHTLY   (run NightlyWorker once now, no charging/idle constraints; logcat tag HakuNightly)
 * ... -a com.bbpillow.haku.debug.DEMO_SEED (two weeks of demo data, see DemoTools.kt; logs DEMO_SEED counts)
 */
class CaptureDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val day = intent.getStringExtra("day") ?: LocalDate.now().toString()
    when (intent.action) {
      "com.bbpillow.haku.debug.START" -> CaptureController.start(context)
      "com.bbpillow.haku.debug.STOP" -> CaptureController.stop(context)
      "com.bbpillow.haku.debug.DUMP" -> CaptureController.runAsync(this, context) { dump(context, day) }
      "com.bbpillow.haku.debug.DIARY" -> CaptureController.runAsync(this, context) { diary(context, day) }
      "com.bbpillow.haku.debug.NIGHTLY" -> NightlyWorker.runNow(context)
      "com.bbpillow.haku.debug.DEMO_SEED" -> CaptureController.runAsync(this, context) { (context.applicationContext as HakuApplication).graph.seedDemo() }
    }
  }

  private fun dump(context: Context, day: String) {
    val zone = ZoneId.systemDefault()
    val date = LocalDate.parse(day)
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val events = CaptureEventStore(CaptureController.connection(context)).query(CaptureQueryInput(from, to))
    Log.i(TAG, "DUMP $day enabled=${CaptureController.isEnabled(context)} events=${events.size}")
    events.forEach { Log.i(TAG, "EVENT ${it.id} ${it.source} place=${it.place} end=${it.endUtcMs}") }
  }

  private fun diary(context: Context, day: String) {
    // runAsync already runs on the capture thread, where the app graph's stores live
    val composer = (context.applicationContext as HakuApplication).graph.onCaptureThread().diary
    val tz = ZoneId.systemDefault().rules.getOffset(LocalDate.parse(day).atStartOfDay()).totalSeconds / 60
    val out = composer.compose(DiaryComposeInput(day, tz))
    Log.i(TAG, "DIARY ${out.header} written=${out.written.size} existing=${out.existing} suppressed=${out.suppressed}")
    composer.readDay(day, tz).forEach { Log.i(TAG, "LINE ${it.text}") }
  }

  private companion object {
    const val TAG = CaptureController.TAG
  }
}
