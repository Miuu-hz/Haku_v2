package com.bbpillow.haku.capture.notification

/**
 * P5.8 shared contract: one chat notification (or one piece of text the user shared/selected), handed from
 * `platform` to core. RAM only: never written to a Store, memory file, log or index (PDPA: no raw chat text kept).
 *
 * @property sourceApp package name, used only for the user's allowlist of chat apps.
 * @property postedUtcMs `Notification.when` (the time the message was sent); the anchor for "พรุ่งนี้", "วันศุกร์".
 *   For selected/shared text it is the time of the selection.
 * @property text title + text joined with a newline, as shown on the lock screen.
 */
data class NotificationTextEvent(
  val sourceApp: String,
  val postedUtcMs: Long,
  val text: String,
)
