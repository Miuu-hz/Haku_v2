package com.bbpillow.haku.platform.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.bbpillow.haku.capture.notification.NotificationAppointmentRule
import com.bbpillow.haku.capture.notification.NotificationTextEvent
import com.bbpillow.haku.platform.notify.AppointmentOfferHooks
import java.util.TimeZone

/**
 * P5.8 "Haku: เพิ่มนัด" in the text-selection menu (ACTION_PROCESS_TEXT). No UI of its own: parse the selected text,
 * hand an offer to [AppointmentOfferHooks.onOffer] (the heads-up then asks the user), or toast when nothing is found.
 * The text is used once in RAM and never stored or logged. The bottom-sheet UI (F4.1) is not part of this.
 */
class ProcessTextActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    val now = System.currentTimeMillis()
    val event = NotificationTextEvent(callingPackage ?: "shared", now, text)
    val offer = NotificationAppointmentRule { System.currentTimeMillis() }
      .offerSelected(event, TimeZone.getDefault().getOffset(now) / 60_000)
    if (offer == null) {
      Toast.makeText(this, "ไม่พบนัดในข้อความนี้", Toast.LENGTH_SHORT).show()
    } else {
      // wiring: Claude (onOffer is set from the Application, see AppointmentOfferHooks)
      AppointmentOfferHooks.onOffer?.invoke(offer, null)
    }
    finish()
  }
}
