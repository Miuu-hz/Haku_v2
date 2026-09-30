package com.bbpillow.haku

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.platform.capture.CaptureController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bbpillow.haku.surface.components.FloatingNav
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.NavItem
import com.bbpillow.haku.surface.home.HomeBriefScreen
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
import com.bbpillow.haku.surface.plus.PlusScreen
import com.bbpillow.haku.surface.timeline.TimelineScreen
import com.bbpillow.haku.surface.timeline.TimelineUiState
import kotlinx.coroutines.launch

private val NavItems = listOf(
  NavItem(HakuIcons.Home, "หน้าแรก"),
  NavItem(HakuIcons.Route, "ไทม์ไลน์"),
  NavItem(HakuIcons.Grid, "ความจำ"),
)

private const val DAY_MS = 86_400_000L

/** Room under the lists so the last row clears the floating nav. */
private val TabPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 100.dp)

/**
 * Three tabs over one [FloatingNav]. Each tab loads from its presenter on the DB thread and holds only the returned state.
 * Haku Plus (P9.4) opens over the tabs from Home's weekly chip or the Memory tab; back closes it.
 */
@Composable
fun MainShell(graph: AppGraph, activity: Activity) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var day by rememberSaveable { mutableStateOf<String?>(null) }
  var plusOpen by rememberSaveable { mutableStateOf(false) }
  BackHandler(enabled = plusOpen) { plusOpen = false }
  Box(Modifier.fillMaxSize()) {
    if (plusOpen) {
      PlusTab(graph, activity) { plusOpen = false }
      return@Box
    }
    when (tab) {
      0 -> HomeTab(graph, CaptureController.isEnabled(activity), { day = it; tab = 1 }, { plusOpen = true })
      1 -> TimelineTab(graph, day) { day = it }
      else -> MemoryTab(graph) { plusOpen = true }
    }
    FloatingNav(NavItems, tab, { tab = it }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
  }
}

@Composable
private fun PlusTab(graph: AppGraph, activity: Activity, onClose: () -> Unit) {
  val state by graph.billing.ui.collectAsState()
  val scope = rememberCoroutineScope()
  LaunchedEffect(Unit) { graph.billing.refresh() } // fresh prices and status each time it opens
  PlusScreen(
    state,
    onBuy = { graph.billing.buy(activity, it) },
    onRestore = { scope.launch { graph.billing.restore() } },
    onBack = onClose,
    contentPadding = TabPadding,
  )
}

@Composable
private fun HomeTab(graph: AppGraph, isCapturing: Boolean, onOpenDay: (String) -> Unit, onOpenPlus: () -> Unit) {
  var state by remember { mutableStateOf<HomeBriefUiState?>(null) }
  // reload when the plan changes (file read at start, a purchase, a restore)
  val entitlements by graph.billing.entitlements.collectAsState()
  LaunchedEffect(entitlements.plan) {
    val now = System.currentTimeMillis()
    state = graph.safeIo {
      // the brief reads yesterday in the morning and today later on; write both before reading
      compose(graph.today(now - DAY_MS))
      compose(graph.today(now))
      home.load(now, profile.load().style)
    }
  }
  val s = state ?: return
  HomeBriefScreen(
    s,
    isCapturing = isCapturing,
    onOpenDay = onOpenDay,
    // ponytail: nothing produces learning cards until P5.x, so these never fire yet
    onReadLearning = {},
    onDismissLearning = { state = s.copy(learning = null) },
    contentPadding = TabPadding,
    onOpenPlus = onOpenPlus,
  )
}

@Composable
private fun TimelineTab(graph: AppGraph, selected: String?, onSelectDay: (String) -> Unit) {
  val today = remember { graph.today(System.currentTimeMillis()) }
  val day = selected ?: today
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<TimelineUiState?>(null) }
  LaunchedEffect(day) { state = graph.safeIo { compose(day); timeline.load(day, today) } }
  val s = state ?: return
  TimelineScreen(
    s,
    onSelectDay = onSelectDay,
    onEdit = { line, text -> scope.launch { graph.safeIo { timeline.edit(s, line, text) }?.let { state = it } } },
    onDelete = { line -> scope.launch { graph.safeIo { timeline.delete(s, line) }?.let { state = it } } },
    contentPadding = TabPadding,
  )
}

@Composable
private fun MemoryTab(graph: AppGraph, onOpenPlus: () -> Unit) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var state by remember { mutableStateOf<TransparencyUiState?>(null) }
  LaunchedEffect(Unit) { state = graph.safeIo { transparency.load() } }
  // SAF: the user picks where the file goes; nothing is sent anywhere (not egress)
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
    if (uri != null) scope.launch {
      val text = graph.safeIo { transparency.exportMarkdown() } ?: return@launch
      withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }
    }
  }
  val s = state ?: return
  TransparencyScreen(
    s,
    onForget = { fact -> scope.launch { graph.safeIo { transparency.forget(fact) }?.let { state = it } } },
    onForgetCategory = { slug -> scope.launch { graph.safeIo { transparency.forgetCategory(slug) }?.let { state = it } } },
    onForgetEverything = { scope.launch { graph.safeIo { transparency.forgetEverything() }?.let { state = it } } },
    onExport = { picker.launch("haku-memory.md") },
    onOpenPlus = onOpenPlus,
    contentPadding = TabPadding,
  )
}
