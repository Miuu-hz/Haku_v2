package com.bbpillow.haku

import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.platform.signals.CalendarWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    AppointmentOfferHooks.onOffer = { request, appName ->
      graph.scope.launch {
        // as the chat card: an overlap goes on the heads-up as "ชนกับ …" (warning is the card's conflict line)
        val args = CalendarAddArgs.parse(request.arguments)?.takeIf { request.action == CalendarAddArgs.ACTION && request.warning == null }
        val conflict = args?.let {
          withContext(Dispatchers.IO) { runCatching { CalendarWriter.conflictLine(this@HakuApplication, it.startUtcMs, it.endUtcMs, graph.tzOffsetMinutes, it.allDay) }.getOrNull() }
        }
        val checked = if (conflict != null) request.copy(warning = conflict) else request
        (graph.actionGate.submit(checked) as? ActionDecision.AwaitingUser)?.let {
          graph.keepOffer(it.proposal, appName) // kept even when notifications are off: Home still asks
          AppointmentOfferNotifier.post(this@HakuApplication, it.proposal)
        }
      }
    }
    AppointmentOfferHooks.confirm = { id -> graph.confirmOffer(id) } // also writes the "นัด …" diary line, as the chat card
    AppointmentOfferHooks.dismiss = { id -> graph.scope.launch { graph.dismissOffer(id) } }
    // No-op until capture was explicitly started once (end of onboarding, CaptureController.start).
    CaptureController.resumeIfEnabled(this)
  }

  /** 5ข: UI hidden or worse frees the chat's models (the chat tab warms them again when shown). */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level >= TRIM_MEMORY_UI_HIDDEN) graph.warmth.stop()
  }
}
