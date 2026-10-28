package com.bbpillow.haku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.bbpillow.haku.platform.BiometricLock
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.onboarding.AppStart
import com.bbpillow.haku.surface.onboarding.OnboardingActions
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.OnboardingScreen
import com.bbpillow.haku.surface.onboarding.OnboardingStep
import com.bbpillow.haku.surface.onboarding.OnboardingUiState
import com.bbpillow.haku.surface.onboarding.PermissionKind
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import kotlinx.coroutines.launch

/** Manifest names behind each consent card (docs/permissions.md). Background location is asked on its own, last. */
private fun manifestNames(kind: PermissionKind): List<String> = when (kind) {
  PermissionKind.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
  PermissionKind.ACTIVITY -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
  PermissionKind.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
  PermissionKind.BACKGROUND_LOCATION -> listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
}

/** What the system has granted right now. Location counts when either precise or approximate was given. */
internal fun grantedKinds(context: Context): Set<PermissionKind> = PermissionKind.entries.filter { kind ->
  manifestNames(kind).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}.toSet()

/**
 * The Android side of [OnboardingActions] (P3.6): system permission dialogs, the model download, the biometric prompt
 * and the first-place answer. Writes (profile, place) run on the DB thread; pure steps run here. On finish: capture
 * starts only with location + activity granted.
 */
@Composable
fun OnboardingHost(graph: AppGraph, activity: FragmentActivity, onDone: (ProfileRecord) -> Unit) {
  val scope = rememberCoroutineScope()
  val downloads = graph.downloader
  var presenter by remember { mutableStateOf<OnboardingPresenter?>(null) }
  var state by remember { mutableStateOf<OnboardingUiState?>(null) }

  // keep the last download report on top of whatever the presenter returned (a save may race a progress tick)
  fun withDownload(p: OnboardingPresenter, s: OnboardingUiState): OnboardingUiState =
    downloads.state.value?.takeIf { it.choice == s.model }?.let { p.downloadUpdate(s, it.fraction, it.paused) } ?: s

  fun write(step: OnboardingPresenter.(OnboardingUiState) -> OnboardingUiState) {
    val s = state ?: return
    val p = presenter ?: return
    scope.launch { graph.safeIo { onboarding.step(s) }?.let { state = withDownload(p, it) } }
  }

  LaunchedEffect(Unit) {
    val ram = graph.ramGb()
    val granted = grantedKinds(activity)
    graph.safeIo {
      val start = onboarding.start(ram, firstPlaceGuess(System.currentTimeMillis()))
      onboarding to onboarding.permissionsResult(start, granted)
    }?.let { (p, s) ->
      presenter = p
      state = withDownload(p, s)
    }
  }
  LaunchedEffect(Unit) {
    downloads.state.collect { d ->
      val p = presenter ?: return@collect
      val s = state ?: return@collect
      if (d != null && d.choice == s.model) state = p.downloadUpdate(s, d.fraction, d.paused)
      d?.error?.let { Toast.makeText(activity, "ดาวน์โหลดหยุด: $it", Toast.LENGTH_LONG).show() }
    }
  }

  val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    val p = presenter ?: return@rememberLauncherForActivityResult
    state = state?.let { p.permissionsResult(it, grantedKinds(activity)) }
  }
  val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    val p = presenter ?: return@rememberLauncherForActivityResult
    val s = p.permissionsResult(state ?: return@rememberLauncherForActivityResult, grantedKinds(activity))
    state = s
    // Android only offers "allow all the time" after foreground location, in a separate step
    if (PermissionKind.BACKGROUND_LOCATION in p.permissionRequests(s) && PermissionKind.LOCATION in s.grantedPermissions) {
      background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
  }

  val p = presenter ?: return
  val s = state ?: return
  val actions = OnboardingActions(
    onNext = { write { next(it) } },
    onBack = { state = p.back(s) },
    onSkip = { write { skip(it) } },
    onTogglePermission = { state = p.togglePermission(s, it) },
    onRequestPermissions = {
      val asks = p.permissionRequests(s)
      val fg = asks.filter { it != PermissionKind.BACKGROUND_LOCATION }.flatMap(::manifestNames)
      when {
        fg.isNotEmpty() -> foreground.launch(fg.toTypedArray())
        PermissionKind.BACKGROUND_LOCATION in asks -> background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      }
    },
    onParticle = { state = p.setParticle(s, it) },
    onAddress = { state = p.setAddress(s, it) },
    onNickname = { state = p.setNickname(s, it) },
    onChooseModel = { state = p.chooseModel(s, it) },
    onStartDownload = {
      downloads.start(s.model)
      state = p.downloadUpdate(s, 0f)
    },
    onPauseDownload = { pause -> if (pause) downloads.pause() else downloads.start(s.model) },
    onEnableLock = {
      if (!BiometricLock.available(activity)) {
        Toast.makeText(activity, "ตั้งล็อกหน้าจอหรือสแกนนิ้วในการตั้งค่าเครื่องก่อน", Toast.LENGTH_LONG).show()
      } else {
        BiometricLock.prompt(activity, "เปิดล็อก Haku") { ok -> if (ok) write { lockResult(it, true) } }
      }
    },
    onConfirmPlace = { label -> write { confirmPlace(it, label) } },
    onRejectPlace = { write { rejectPlace(it) } },
  )
  Column {
    OnboardingScreen(s, actions, Modifier.weight(1f), logo = painterResource(R.mipmap.ic_launcher_foreground))
    // debug builds only (release DemoTools is a no-op): skip onboarding with the demo persona
    if (s.step == OnboardingStep.WELCOME) {
      DemoSeedButton(graph, { scope.launch { graph.safeIo { profile.load() }?.let(onDone) } }, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
    }
    if (s.complete) {
      CapsuleButton(
        "เริ่มใช้ Haku",
        {
          scope.launch {
            if (AppStart.shouldStartCapture(grantedKinds(activity))) CaptureController.start(activity)
            graph.safeIo { profile.load() }?.let(onDone)
          }
        },
        Modifier.fillMaxWidth().padding(20.dp),
      )
    }
  }
}
