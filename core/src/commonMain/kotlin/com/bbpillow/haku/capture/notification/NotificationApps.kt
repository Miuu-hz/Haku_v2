package com.bbpillow.haku.capture.notification

/** A chat app Haku can read notifications from, once the user turns it on. [messageOnly] apps also post likes and posts. */
data class NotificationApp(
  val packageName: String,
  val name: String,
  val mono: String,
  val note: String,
  val messageOnly: Boolean = false,
)

/** P5.8: consent is per app, so every app starts off and every app has its own switch. */
object NotificationApps {
  private const val CHAT = "แชท"
  private const val PRIVATE_AND_GROUP = "แชทส่วนตัวและกลุ่ม"
  private const val SMS = "SMS"
  private const val MESSAGES_ONLY = "เฉพาะข้อความ"
  private const val MESSAGES_NOT_POSTS = "เฉพาะข้อความ ไม่รวมไลก์และโพสต์"

  val ALL: List<NotificationApp> = listOf(
    NotificationApp("jp.naver.line.android", "LINE", "L", PRIVATE_AND_GROUP),
    NotificationApp("com.facebook.orca", "Messenger", "M", PRIVATE_AND_GROUP),
    NotificationApp("com.whatsapp", "WhatsApp", "W", CHAT),
    NotificationApp("org.telegram.messenger", "Telegram", "T", CHAT),
    NotificationApp("com.google.android.apps.messaging", "Google Messages", "G", SMS),
    NotificationApp("com.samsung.android.messaging", "Samsung Messages", "S", SMS),
    NotificationApp("com.instagram.android", "Instagram", "I", MESSAGES_NOT_POSTS, messageOnly = true),
    NotificationApp("com.twitter.android", "X", "X", MESSAGES_NOT_POSTS, messageOnly = true),
    NotificationApp("com.linkedin.android", "LinkedIn", "in", MESSAGES_ONLY, messageOnly = true),
    NotificationApp("com.discord", "Discord", "D", MESSAGES_ONLY, messageOnly = true),
    NotificationApp("com.Slack", "Slack", "S", MESSAGES_ONLY, messageOnly = true),
    NotificationApp("com.microsoft.teams", "Microsoft Teams", "T", MESSAGES_ONLY, messageOnly = true),
    NotificationApp("com.viber.voip", "Viber", "V", CHAT),
    NotificationApp("com.kakao.talk", "KakaoTalk", "K", CHAT),
    NotificationApp("com.tencent.mm", "WeChat", "W", CHAT),
  )

  private val KNOWN: Set<String> = ALL.map { it.packageName }.toSet()

  /** The listener's first check, before any extras. Both must hold: stored switches are untrusted, so the list still limits them. */
  fun mayRead(packageName: String, enabled: Set<String>): Boolean = packageName in KNOWN && packageName in enabled
}

/** Which apps the user turned on. An app that was never set is off. Must answer synchronously: the listener asks before touching any extras. */
interface NotificationAppsRepository {
  fun enabled(): Set<String>
  fun setEnabled(packageName: String, on: Boolean)
}
