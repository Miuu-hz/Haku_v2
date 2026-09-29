package com.bbpillow.haku

import android.app.Application
import com.bbpillow.haku.platform.capture.CaptureController

class HakuApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    // No-op until capture was explicitly started once (CaptureController.start).
    CaptureController.resumeIfEnabled(this)
  }
}
