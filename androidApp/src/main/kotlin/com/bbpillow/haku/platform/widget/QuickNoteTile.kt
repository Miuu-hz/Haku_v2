package com.bbpillow.haku.platform.widget

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.TileService
import com.bbpillow.haku.QuickNoteActivity

/**
 * P3.5 Quick Settings tile "บันทึกด่วน": the lock-screen way in (the widget is home-screen only, and the brief
 * notification shows no actions on the lock screen). Opens QuickNoteActivity over the keyguard.
 * ponytail: relies on SystemUI not asking for unlock before a showWhenLocked activity; check on the Fold5.
 */
class QuickNoteTile : TileService() {
  override fun onClick() {
    val intent = QuickNoteActivity.intent(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}
