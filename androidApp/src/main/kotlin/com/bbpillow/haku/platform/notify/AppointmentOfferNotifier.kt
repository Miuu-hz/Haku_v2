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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bbpillow.haku.MainActivity
import com.bbpillow.haku.R
import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.surface.chat.AppointmentCommand

/**
 * P5.8 heads-up "เพิ่มนัดนี้ลงปฏิทินไหม" with [เพิ่ม] [ไม่ใช่] (as [IntentNotifier]: private on the lock screen, because the
 * title came from a chat). The buttons carry only the proposal id; the request is in the gate, and in the kept offer once
 * the process has died.
 */
object AppointmentOfferNotifier {
  private const val CHANNEL_ID = "appointment-offers"
  private const val CHANNEL_NAME = "นัดจากแชท"
  private const val PUBLIC_TEXT = "Haku พบข้อความที่น่าจะเป็นนัด"
  private const val NOTIFICATION_BASE_ID = 7600
  private const val EXPIRED_TEXT = "ข้อเสนอนี้หมดอายุ เปิดแชทแล้วพิมพ์นัดอีกครั้ง"
  private const val RESULT_TIMEOUT_MS = 8_000L

  internal const val ACTION_CONFIRM = "com.bbpillow.haku.offer.CONFIRM"
  internal const val ACTION_DISMISS = "com.bbpillow.haku.offer.DISMISS"
  internal const val EXTRA_PROPOSAL_ID = "proposal-id"

  fun notificationId(proposalId: String): Int = NOTIFICATION_BASE_ID + (proposalId.hashCode() and 0xFFF)

  /** Returns false when notifications are not allowed; the kept offer still waits on Home. */
  @SuppressLint("MissingPermission") // checked in canPost
  fun post(context: Context, proposal: ActionProposalRecord): Boolean {
    if (!canPost(context)) return false
    val request = proposal.request
    val text = listOfNotNull(request.title, request.detail, request.warning).joinToString("\n")
    val notification = base(context)
      .setContentTitle(AppointmentCommand.PROMPT_TEXT)
      .setContentText("${request.title} · ${request.detail}")
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .addAction(0, "เพิ่ม", actionIntent(context, ACTION_CONFIRM, proposal.id))
      .addAction(0, "ไม่ใช่", actionIntent(context, ACTION_DISMISS, proposal.id))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
      .build()
    NotificationManagerCompat.from(context).notify(notificationId(proposal.id), notification)
    return true
  }

  /** After a tap on [เพิ่ม]: replaces the offer with the Thai result (no buttons), gone after a few seconds. */
  @SuppressLint("MissingPermission") // checked in canPost
  fun showResult(context: Context, proposalId: String, message: String) {
    if (!canPost(context)) return
    val notification = base(context)
      .setContentTitle(message)
      .setOnlyAlertOnce(true)
      .setTimeoutAfter(RESULT_TIMEOUT_MS)
      .build()
    NotificationManagerCompat.from(context).notify(notificationId(proposalId), notification)
  }

  /** The offer is gone (its appointment ended, or it was already decided): same slot, no buttons. */
  fun showExpired(context: Context, proposalId: String) = showResult(context, proposalId, EXPIRED_TEXT)

  fun cancel(context: Context, proposalId: String) = NotificationManagerCompat.from(context).cancel(notificationId(proposalId))

  private fun canPost(context: Context): Boolean {
    val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel) // no-op once it exists
    val nm = NotificationManagerCompat.from(context)
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return granted && nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
  }

  private fun base(context: Context): NotificationCompat.Builder {
    val open = PendingIntent.getActivity(
      context,
      NOTIFICATION_BASE_ID,
      Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_haku).setContentTitle(PUBLIC_TEXT).build()
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_haku)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setPublicVersion(lockScreen)
      .setContentIntent(open)
      .setAutoCancel(true)
  }

  /** Explicit to our own non-exported receiver; the data URI keeps one PendingIntent per (proposal, button). */
  private fun actionIntent(context: Context, action: String, proposalId: String): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, AppointmentOfferReceiver::class.java)
      .setAction(action)
      .setData(Uri.fromParts("haku-offer", proposalId, action))
      .putExtra(EXTRA_PROPOSAL_ID, proposalId),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
  )
}
