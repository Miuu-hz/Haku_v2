package com.bbpillow.haku

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.components.FloatingNav
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.NavItem
import com.bbpillow.haku.surface.home.HomeBriefScreen
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
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

/** Three tabs over one [FloatingNav]. Each tab loads from its presenter on the DB thread and holds only the returned state. */
@Composable
fun MainShell(graph: AppGraph, context: Context) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var day by rememberSaveable { mutableStateOf<String?>(null) }
  Box(Modifier.fillMaxSize()) {
    when (tab) {
      0 -> HomeTab(graph, CaptureController.isEnabled(context)) { day = it; tab = 1 }
      1 -> TimelineTab(graph, day) { day = it }
      else -> MemoryTab(graph)
    }
    FloatingNav(NavItems, tab, { tab = it }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp))
  }
}

@Composable
private fun HomeTab(graph: AppGraph, isCapturing: Boolean, onOpenDay: (String) -> Unit) {
  var state by remember { mutableStateOf<HomeBriefUiState?>(null) }
  LaunchedEffect(Unit) {
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
private fun MemoryTab(graph: AppGraph) {
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<TransparencyUiState?>(null) }
  LaunchedEffect(Unit) { state = graph.safeIo { transparency.load() } }
  val s = state ?: return
  TransparencyScreen(
    s,
    onForget = { fact -> scope.launch { graph.safeIo { transparency.forget(fact) }?.let { state = it } } },
    contentPadding = TabPadding,
  )
}
