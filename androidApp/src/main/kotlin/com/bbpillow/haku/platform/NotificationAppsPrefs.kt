package com.bbpillow.haku.platform

import android.content.Context
import com.bbpillow.haku.capture.notification.NotificationAppsRepository

/** P5.8 per-app switches. SharedPreferences answers from memory, so the listener can ask on every notification. */
class NotificationAppsPrefs(context: Context) : NotificationAppsRepository {
  private val prefs = context.applicationContext.getSharedPreferences("notification_apps", Context.MODE_PRIVATE)

  override fun enabled(): Set<String> = prefs.getStringSet(ENABLED, emptySet()).orEmpty()

  override fun setEnabled(packageName: String, on: Boolean) {
    val next = if (on) enabled() + packageName else enabled() - packageName
    prefs.edit().putStringSet(ENABLED, next).apply()
  }

  private companion object {
    const val ENABLED = "enabled"
  }
}
