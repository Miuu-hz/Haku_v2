package com.bbpillow.haku.platform.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.bbpillow.haku.capture.notification.NotificationAppointmentRule
import com.bbpillow.haku.capture.notification.NotificationTextEvent
import com.bbpillow.haku.platform.notify.AppointmentOfferHooks
import java.util.TimeZone

/**
 * P5.8: reads chat notifications of the allowlisted apps only, looks for an appointment, and hands any offer to
 * [AppointmentOfferHooks.onOffer]. Notification access is off until the user enables it in system settings (PDPA ม.19).
 *
 * Reads just EXTRA_TITLE + the last message (or EXTRA_TEXT). Nothing is stored and nothing is logged: no text, titles
 * or package names, in any build type.
 */
class HakuNotificationListener : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName !in NotificationAppointmentRule.CHAT_APPS) return // before touching any extras
    val n = sbn.notification ?: return
    if (sbn.isOngoing || (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)) != 0) return
    val last = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages?.lastOrNull() }.getOrNull()
    // social apps also post likes/follows/posts: only real messages may become offers
    if (sbn.packageName in NotificationAppointmentRule.MESSAGE_ONLY_APPS && n.category != Notification.CATEGORY_MESSAGE && last == null) return
    val extras = n.extras ?: return
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val body = last?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    if (body.isNullOrBlank()) return
    val postedUtcMs = last?.timestamp?.takeIf { it > 0 } ?: n.`when`.takeIf { it > 0 } ?: sbn.postTime
    val event = NotificationTextEvent(sbn.packageName, postedUtcMs, "$title\n$body") // first line = sender, see the rule
    val tzOffsetMinutes = TimeZone.getDefault().getOffset(postedUtcMs) / 60_000
    // wiring: Claude (onOffer is set from the Application, see AppointmentOfferHooks)
    rule.offer(event, tzOffsetMinutes)?.let { AppointmentOfferHooks.onOffer?.invoke(it) }
  }

  private companion object {
    // One rule for the process so the re-post guard survives the system rebinding the service. Callbacks arrive on the main thread.
    val rule = NotificationAppointmentRule { System.currentTimeMillis() }
  }
}
