package com.bbpillow.haku.capture.notification

import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.ActionRequestInput
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.thinking.appointment.AppointmentParser

/**
 * P5.8 level-0 filter: one chat notification (or one piece of selected text) in, at most one `calendar.add` offer out.
 * Pure rules in RAM (no model, no I/O, no Store): the text is parsed and dropped, only the returned [ActionRequestInput]
 * (title + times) goes on to the action gate, and nothing is written until the user taps add.
 *
 * Date anchor is [NotificationTextEvent.postedUtcMs] (when the message was sent), never the wall clock, so "พรุ่งนี้"
 * sent at 23:59 and read the next morning still means the day after it was sent. [nowUtcMs] is used only to drop
 * appointments that are already over.
 *
 * Not thread-safe (the recent-offer guard): call from one thread, as the notification listener does.
 */
// ponytail: the guard is a plain bounded list; no timestamps, the oldest key simply falls out after RECENT_OFFER_MAX offers.
class NotificationAppointmentRule(private val nowUtcMs: () -> Long) {
  private val recentKeys = ArrayDeque<String>()

  /**
   * Notification path. [NotificationTextEvent.text] is `"<title>\n<body>"`; the first line (sender / group name) is
   * dropped so a name never ends up in the appointment title. Null = not an allowlisted chat app, our own app, not an
   * appointment, already past, or the same offer was made recently.
   */
  fun offer(
    event: NotificationTextEvent,
    tzOffsetMinutes: Int,
    origin: ActionOrigin = ActionOrigin.NOTIFICATION_RULE,
  ): ActionRequestInput? {
    if (origin == ActionOrigin.SHARED_TEXT) {
      return build(event.text, event.postedUtcMs, tzOffsetMinutes, ActionOrigin.SHARED_TEXT)
    }
    if (event.sourceApp == OWN_PACKAGE || event.sourceApp !in CHAT_APPS) return null
    val body = event.text.substringAfter('\n', event.text)
    val request = build(body, event.postedUtcMs, tzOffsetMinutes, origin) ?: return null
    if (request.dedupeKey in recentKeys) return null
    recentKeys.addLast(request.dedupeKey)
    if (recentKeys.size > RECENT_OFFER_MAX) recentKeys.removeFirst()
    return request
  }

  /** Selected / shared text the user chose themselves: any source app, no re-post guard, origin SHARED_TEXT. */
  fun offerSelected(event: NotificationTextEvent, tzOffsetMinutes: Int): ActionRequestInput? =
    offer(event, tzOffsetMinutes, ActionOrigin.SHARED_TEXT)

  private fun build(text: String, anchorUtcMs: Long, tzOffsetMinutes: Int, origin: ActionOrigin): ActionRequestInput? {
    val draft = AppointmentParser.parse(text, anchorUtcMs, tzOffsetMinutes) ?: return null
    if (draft.startUtcMs <= nowUtcMs()) return null
    return ActionRequestInput(
      action = CALENDAR_ADD,
      arguments = buildMap {
        put("title", draft.title)
        put("startUtcMs", draft.startUtcMs.toString())
        put("endUtcMs", draft.endUtcMs.toString())
        if (draft.allDay) put("allDay", "true")
      },
      title = draft.title,
      detail = detailOf(draft.startUtcMs, draft.endUtcMs, tzOffsetMinutes, draft.allDay),
      origin = origin,
      dedupeKey = "$CALENDAR_ADD:${draft.title}|${draft.startUtcMs}",
    )
  }

  // Same labels as surface/chat/AppointmentCommand: "<weekday> <date> · <start>–<end>".
  private fun detailOf(startUtcMs: Long, endUtcMs: Long, tzOffsetMinutes: Int, allDay: Boolean = false): String {
    val startLocalMs = startUtcMs + tzOffsetMinutes * 60_000L
    val endLocalMs = endUtcMs + tzOffsetMinutes * 60_000L
    val startDay = MemoryFormat.dayOf(startLocalMs)
    return if (allDay) {
      val lastDay = MemoryFormat.dayOf(endLocalMs - 1L)
      val dayLabel = if (startDay == lastDay) {
        "${ThaiDateFormat.shortWeekday(startDay)} ${ThaiDateFormat.shortDate(startDay)}"
      } else {
        val (sY, sM, sD) = startDay.split('-').map { it.toInt() }
        val (lY, lM, lD) = lastDay.split('-').map { it.toInt() }
        if (sY == lY && sM == lM) {
          "$sD–$lD ${ThaiDateFormat.shortDate(startDay).substringAfter(' ')}"
        } else {
          "${ThaiDateFormat.shortDate(startDay)}–${ThaiDateFormat.shortDate(lastDay)}"
        }
      }
      "$dayLabel · ทั้งวัน"
    } else {
      val dayLabel = "${ThaiDateFormat.shortWeekday(startDay)} ${ThaiDateFormat.shortDate(startDay)}"
      val startHhmm = MemoryFormat.formatUtc(startLocalMs).substring(11, 16)
      val endHhmm = MemoryFormat.formatUtc(endLocalMs).substring(11, 16)
      "$dayLabel · $startHhmm–$endHhmm"
    }
  }

  companion object {
    const val CALENDAR_ADD = "calendar.add"
    const val OWN_PACKAGE = "com.bbpillow.haku"

    /** How many recent offer keys the re-post guard remembers. */
    const val RECENT_OFFER_MAX = 20

    /**
     * The only apps whose notifications are ever read (LINE, Messenger, WhatsApp, Telegram, Google Messages, Samsung Messages,
     * Instagram, X, LinkedIn, Discord, Slack, Microsoft Teams, Viber, KakaoTalk, WeChat).
     */
    val CHAT_APPS: Set<String> = setOf(
      "jp.naver.line.android",
      "com.facebook.orca",
      "com.whatsapp",
      "org.telegram.messenger",
      "com.google.android.apps.messaging",
      "com.samsung.android.messaging",
      "com.instagram.android",
      "com.twitter.android",
      "com.linkedin.android",
      "com.discord",
      "com.Slack",
      "com.microsoft.teams",
      "com.viber.voip",
      "com.kakao.talk",
      "com.tencent.mm",
    )

    /** Social apps that also post likes, follows and posts: only their real messages may become offers. */
    val MESSAGE_ONLY_APPS: Set<String> = setOf(
      "com.instagram.android",
      "com.twitter.android",
      "com.linkedin.android",
      "com.discord",
      "com.Slack",
      "com.microsoft.teams",
    )
  }
}
