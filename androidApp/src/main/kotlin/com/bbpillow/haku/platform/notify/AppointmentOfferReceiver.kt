package com.bbpillow.haku.platform.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.action.ActionStatus
import kotlinx.coroutines.launch

/**
 * P5.8 [เพิ่ม] / [ไม่ใช่] on [AppointmentOfferNotifier]'s heads-up, through [AppointmentOfferHooks] to the action gate.
 * Only the user's tap on [เพิ่ม] can lead to a calendar write; an offer no longer kept = expired.
 */
class AppointmentOfferReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getStringExtra(AppointmentOfferNotifier.EXTRA_PROPOSAL_ID) ?: return
    val app = context.applicationContext
    when (intent.action) {
      AppointmentOfferNotifier.ACTION_DISMISS -> {
        // wiring: Claude (confirm/dismiss are set from the Application, see AppointmentOfferHooks)
        AppointmentOfferHooks.dismiss?.invoke(id)
        AppointmentOfferNotifier.cancel(app, id)
      }
      AppointmentOfferNotifier.ACTION_CONFIRM -> {
        val pending = goAsync()
        (app as HakuApplication).graph.scope.launch {
          try {
            // wiring: Claude (confirm/dismiss are set from the Application, see AppointmentOfferHooks)
            val record = AppointmentOfferHooks.confirm?.invoke(id)
            when {
              record == null -> AppointmentOfferNotifier.showExpired(app, id)
              record.status == ActionStatus.DONE -> AppointmentOfferNotifier.showResult(app, id, record.message ?: DONE_TEXT)
              else -> AppointmentOfferNotifier.showResult(app, id, record.message ?: FAILED_TEXT) // NEEDS_PERMISSION / FAILED / BLOCKED
            }
          } finally {
            pending.finish()
          }
        }
      }
    }
  }

  private companion object {
    const val DONE_TEXT = "เพิ่มลงปฏิทินแล้ว"
    const val FAILED_TEXT = "เพิ่มนัดไม่สำเร็จ เปิดแอป Haku เพื่อลองอีกครั้ง"
  }
}
