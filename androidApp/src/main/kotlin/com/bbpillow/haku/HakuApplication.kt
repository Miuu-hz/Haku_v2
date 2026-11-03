package com.bbpillow.haku

import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.platform.notify.AppointmentOfferHooks
import com.bbpillow.haku.platform.notify.AppointmentOfferNotifier
import kotlinx.coroutines.launch
import android.app.Application
import com.bbpillow.haku.platform.capture.CaptureController

class HakuApplication : Application() {
  /** The app's stores and presenters (manual DI). Cheap to build: nothing opens until first use. */
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    // P5.8 chat notifications / selected text -> the action gate -> heads-up offer; the user's tap confirms.
    AppointmentOfferHooks.onOffer = { request ->
      graph.scope.launch {
        (graph.actionGate.submit(request) as? ActionDecision.AwaitingUser)?.let { AppointmentOfferNotifier.post(this@HakuApplication, it.proposal) }
      }
    }
    AppointmentOfferHooks.confirm = { id -> graph.actionGate.confirm(id) }
    AppointmentOfferHooks.dismiss = { id -> graph.actionGate.dismiss(id) }
    // No-op until capture was explicitly started once (end of onboarding, CaptureController.start).
    CaptureController.resumeIfEnabled(this)
  }

  /** 5ข: UI hidden or worse frees the chat's models (the chat tab warms them again when shown). */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_UI_HIDDEN) graph.warmth.stop()
  }
}
