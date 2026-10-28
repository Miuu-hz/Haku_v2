package com.bbpillow.haku

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bbpillow.haku.platform.BiometricLock
import com.bbpillow.haku.platform.NightlyWorker
import com.bbpillow.haku.platform.notify.BriefWorker
import com.bbpillow.haku.platform.widget.HakuWidget
import androidx.work.ExistingWorkPolicy
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ScreenBackdrop
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.AppStart
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.StartScreen
import com.bbpillow.haku.surface.theme.HakuTheme
import androidx.compose.material3.Text
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

  /** P3.5: edits, deletes, lock or capture switched in the app show on the widget once the user leaves. */
  override fun onStop() {
    super.onStop()
    HakuWidget.refresh(this)
  }
}

@Composable
private fun AppRoot(graph: AppGraph, activity: FragmentActivity) {
  var record by remember { mutableStateOf<ProfileRecord?>(null) }
  var unlocked by rememberSaveable { mutableStateOf(false) }
  var loadAttempt by remember { mutableIntStateOf(0) }
  var loadFailed by remember { mutableStateOf(false) }
  // never default to ProfileRecord() on a failed read: that would look "not onboarded", skip the lock and overwrite the real profile
  LaunchedEffect(loadAttempt) {
    loadFailed = false
    record = graph.safeIo { profile.load() }
    loadFailed = record == null
  }
  if (loadFailed) {
    LoadFailed { loadAttempt++ }
    return
  }
  val profile = record ?: return
  val lockOn = profile.lockEnabled
  // the app switcher and screenshots must not show content while the lock is on
  LaunchedEffect(lockOn) {
    if (lockOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }
  DisposableEffect(activity, lockOn) {
    var stoppedAt = 0L
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) stoppedAt = SystemClock.elapsedRealtime()
      if (event == Lifecycle.Event.ON_START && stoppedAt != 0L) {
        if (lockOn && AppStart.shouldRelock(SystemClock.elapsedRealtime() - stoppedAt)) unlocked = false
        stoppedAt = 0L
      }
    }
    activity.lifecycle.addObserver(observer)
    onDispose { activity.lifecycle.removeObserver(observer) }
  }
  when (AppStart.screen(profile, unlocked)) {
    StartScreen.ONBOARDING -> OnboardingHost(graph, activity) { done ->
      unlocked = true // they just passed the prompt while turning the lock on
      record = done
    }
    StartScreen.LOCKED -> LockGate(activity) { unlocked = true }
    StartScreen.MAIN -> {
      LaunchedEffect(Unit) {
        (profile.model as? ActiveModel.Catalog)?.let { graph.downloader.resumeIfPartial(it.choice) }
        if (AppStart.shouldStartCaptureOnMain(grantedKinds(activity), CaptureController.isEnabled(activity))) CaptureController.start(activity)
        NightlyWorker.schedule(activity) // onboarded from here on; KEEP = no-op after the first time
        BriefWorker.sync(activity, profile, ExistingWorkPolicy.KEEP) // P5.2: pending briefs stay; a lost chain is re-made
        graph.billing.refresh() // P9.4 restore on start; offline = keep the saved plan within its 7-day grace
      }
      MainShell(graph, activity) { updated ->
        unlocked = true
        record = updated
      }
    }
  }
}

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
  Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text("เปิดข้อมูลของคุณไม่สำเร็จ ข้อมูลยังอยู่ ลองใหม่อีกครั้ง")
      CapsuleButton("ลองใหม่", onRetry)
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
