package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.bbpillow.haku.platform.signals.CalendarWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chat appointment stress over adb (Thai text cannot be typed with adb input). Logcat tag `HakuAppt`:
 * `adb shell am broadcast -n com.bbpillow.haku/.AppointmentDebugReceiver -a com.bbpillow.haku.debug.APPT --es q "'…'"`
 * `--ez write true` also inserts the event into the device calendar, reads it back and deletes it again.
 */
class AppointmentDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val q = intent.getStringExtra("q").orEmpty()
    val write = intent.getBooleanExtra("write", false)
    val app = context.applicationContext as HakuApplication
    val pending = goAsync()
    app.graph.scope.launch {
      try {
        val t0 = System.currentTimeMillis()
        val tz = app.graph.tzOffsetMinutes
        val p = app.graph.safeIo { appointmentCommand.tryPropose(q, t0, tz) }?.appointment
        val conflicts = p?.let { withContext(Dispatchers.IO) { CalendarWriter.conflicts(app, it.startUtcMs, it.endUtcMs) } }.orEmpty()
        var written = "-"
        if (p != null && write) {
          written = withContext(Dispatchers.IO) {
            val id = CalendarWriter.insert(app, p.title, p.startUtcMs, p.endUtcMs) ?: return@withContext "insert-failed"
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val back = app.contentResolver.query(uri, arrayOf(CalendarContract.Events.TITLE), null, null, null)
              ?.use { if (it.moveToFirst()) it.getString(0) else null }
            val reminders = CalendarContract.Reminders.query(app.contentResolver, id, arrayOf(CalendarContract.Reminders.MINUTES))
              ?.use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
            val deleted = app.contentResolver.delete(uri, null, null)
            "id=$id readBack=$back reminders=$reminders deleted=$deleted"
          }
        }
        Log.i(TAG, "q=$q | title=${p?.title} | day=${p?.dayLabel} | time=${p?.timeLabel} | conflicts=${conflicts.map { it.title }} | write=$written | ms=${System.currentTimeMillis() - t0}")
      } catch (t: Throwable) {
        Log.e(TAG, "q=$q failed", t)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    const val ACTION = "com.bbpillow.haku.debug.APPT"
    const val TAG = "HakuAppt"
  }
}
