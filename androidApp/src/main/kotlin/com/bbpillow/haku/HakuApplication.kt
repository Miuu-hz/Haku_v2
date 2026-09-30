package com.bbpillow.haku

import android.app.Application
import com.bbpillow.haku.platform.capture.CaptureController

class HakuApplication : Application() {
  /** The app's stores and presenters (manual DI). Cheap to build: nothing opens until first use. */
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
    // No-op until capture was explicitly started once (end of onboarding, CaptureController.start).
    CaptureController.resumeIfEnabled(this)
  }
}
