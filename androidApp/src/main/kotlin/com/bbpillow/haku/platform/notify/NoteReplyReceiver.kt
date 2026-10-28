package com.bbpillow.haku.platform.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.platform.widget.HakuWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P3.5 "จดเพิ่ม" on the brief notification: the typed reply becomes today's user note, then the notification goes. */
class NoteReplyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString() ?: return
    val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
    val pending = goAsync()
    val app = context.applicationContext
    (app as HakuApplication).graph.scope.launch {
      try {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (HakuWidget.saveNote(app, text)) {
          nm.cancel(id)
        } else {
          // re-post the same notification: stops the reply spinner and keeps the action for another try
          nm.activeNotifications.firstOrNull { it.id == id }?.let { nm.notify(id, it.notification) }
          withContext(Dispatchers.Main) { Toast.makeText(app, "จดไม่สำเร็จ ลองอีกครั้ง", Toast.LENGTH_SHORT).show() }
        }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val KEY_TEXT = "note"
    private const val EXTRA_NOTIFICATION_ID = "notification-id"

    /** Mutable so the system can add the RemoteInput result; explicit, to our own non-exported receiver. */
    fun pendingIntent(context: Context, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
      context,
      notificationId,
      Intent(context, NoteReplyReceiver::class.java).putExtra(EXTRA_NOTIFICATION_ID, notificationId),
      PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
