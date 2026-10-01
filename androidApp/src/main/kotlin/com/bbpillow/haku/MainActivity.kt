package com.bbpillow.haku

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.bbpillow.haku.platform.BiometricLock
import com.bbpillow.haku.platform.NightlyWorker
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ScreenBackdrop
import com.bbpillow.haku.surface.onboarding.AppStart
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.StartScreen
import com.bbpillow.haku.surface.theme.HakuTheme
import kotlinx.coroutines.CancellationException

/** The app: onboarding until done, then the D11 lock if on, then Home / Timeline / Memory. FragmentActivity for BiometricPrompt. */
class MainActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val graph = (application as HakuApplication).graph
    setContent {
      HakuTheme {
        // safeDrawingPadding consumes the insets, so the screens below do not pad twice
        ScreenBackdrop(Modifier.fillMaxSize()) {
          Box(Modifier.fillMaxSize().safeDrawingPadding()) { AppRoot(graph, this@MainActivity) }
        }
      }
    }
  }
}

@Composable
private fun AppRoot(graph: AppGraph, activity: FragmentActivity) {
  var record by remember { mutableStateOf<ProfileRecord?>(null) }
  var unlocked by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) { record = graph.safeIo { profile.load() } ?: ProfileRecord() }
  val profile = record ?: return
  when (AppStart.screen(profile, unlocked)) {
    StartScreen.ONBOARDING -> OnboardingHost(graph, activity) { done ->
      unlocked = true // they just passed the prompt while turning the lock on
      record = done
    }
    StartScreen.LOCKED -> LockGate(activity) { unlocked = true }
    StartScreen.MAIN -> {
      LaunchedEffect(Unit) {
        graph.downloader.resumeIfPartial(profile.model)
        if (AppStart.shouldStartCaptureOnMain(grantedKinds(activity), CaptureController.isEnabled(activity))) CaptureController.start(activity)
        NightlyWorker.schedule(activity) // onboarded from here on; KEEP = no-op after the first time
        graph.billing.refresh() // P9.4 restore on start; offline = keep the saved plan within its 7-day grace
      }
      MainShell(graph, activity)
    }
  }
}

@Composable
private fun LockGate(activity: FragmentActivity, onUnlocked: () -> Unit) {
  val ask = { BiometricLock.prompt(activity, "ปลดล็อก Haku") { if (it) onUnlocked() } }
  // screen lock removed since: nothing left to check against, so the gate cannot hold
  LaunchedEffect(Unit) { if (BiometricLock.available(activity)) ask() else onUnlocked() }
  Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) { CapsuleButton("ปลดล็อก", ask) }
}

/** [AppGraph.io] that logs and returns null on failure, for screen loads: a broken read leaves the screen as it was. */
suspend fun <T> AppGraph.safeIo(block: AppGraph.Data.() -> T): T? = try {
  io(block)
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  Log.e("HakuShell", "data call failed", e)
  null
}
