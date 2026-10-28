package com.bbpillow.haku.platform.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bbpillow.haku.MainActivity
import com.bbpillow.haku.R
import com.bbpillow.haku.thinking.intent.IntentFireOutput

/** P5.3 posts a fired intention on channel "เตือนตามเงื่อนไข" (as [BriefNotifier]: private on the lock screen). */
object IntentNotifier {
  private const val CHANNEL_ID = "intent-reminders"
  private const val CHANNEL_NAME = "เตือนตามเงื่อนไข"
  private const val PUBLIC_TEXT = "Haku มีเรื่องเตือนตามที่คุณตั้งไว้"
  private const val NOTIFICATION_BASE_ID = 7400

  @SuppressLint("MissingPermission") // checked below
  fun post(context: Context, fired: IntentFireOutput): Boolean {
    val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel) // no-op once it exists
    val nm = NotificationManagerCompat.from(context)
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!granted || !nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
    val open = PendingIntent.getActivity(
      context,
      NOTIFICATION_BASE_ID,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_haku).setContentTitle(PUBLIC_TEXT).build()
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_haku)
      .setContentTitle(fired.title)
      .setContentText(fired.text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(fired.text))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(lockScreen)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    nm.notify(NOTIFICATION_BASE_ID + (fired.intentionId.hashCode() and 0xFF), notification)
    return true
  }
}
