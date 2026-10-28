package com.bbpillow.haku.platform.notify

import android.Manifest
import android.annotation.SuppressLint
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
import com.bbpillow.haku.surface.settings.BriefKind
import com.bbpillow.haku.surface.settings.BriefNoticeOutput
import com.bbpillow.haku.surface.settings.DailyBrief

/** P5.2 posts the daily brief on channel "สรุปประจำวัน". Text is built in core and never leaves the device. */
object BriefNotifier {
  private const val CHANNEL_ID = "daily-brief"
  private const val CHANNEL_NAME = "สรุปประจำวัน"

  /** POST_NOTIFICATIONS granted (Android 13+) and the user has not muted the app or the channel. */
  fun canPost(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val nm = NotificationManagerCompat.from(context)
    val channelOn = nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    return granted && nm.areNotificationsEnabled() && channelOn
  }

  @SuppressLint("MissingPermission") // checked by canPost
  fun post(context: Context, kind: BriefKind, notice: BriefNoticeOutput): Boolean {
    ensureChannel(context)
    if (!canPost(context)) return false
    // a fresh activity opens on Home; the D11 lock applies as on any start
    val open = PendingIntent.getActivity(
      context,
      kind.ordinal,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_haku)
      .setContentTitle(DailyBrief.PUBLIC_TEXT)
      .build()
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_haku)
      .setContentTitle(notice.title)
      .setContentText(notice.text.lineSequence().first())
      .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(lockScreen)
      .setContentIntent(open)
      .setAutoCancel(true)
      .build()
    NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE_ID + kind.ordinal, notification)
    return true
  }

  private fun ensureChannel(context: Context) {
    val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
      lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel) // no-op once it exists
  }

  private const val NOTIFICATION_BASE_ID = 7300
}
