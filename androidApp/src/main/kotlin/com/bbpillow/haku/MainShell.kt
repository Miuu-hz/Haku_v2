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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.platform.capture.CaptureController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bbpillow.haku.surface.components.FloatingNav
import com.bbpillow.haku.surface.components.FloatingNavHeight
import com.bbpillow.haku.surface.chat.ChatAnswerOutput
import com.bbpillow.haku.surface.chat.ChatAnswerSource
import com.bbpillow.haku.surface.chat.ChatQuestionInput
import com.bbpillow.haku.surface.chat.ChatScreen
import com.bbpillow.haku.surface.chat.ChatTurn
import com.bbpillow.haku.surface.node.HomeNodeScreen
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
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
  NavItem(HakuIcons.Chat, "ถาม Haku"),
  NavItem(HakuIcons.Grid, "ความจำ"),
)
private const val TAB_HOME = 0
private const val TAB_TIMELINE = 1
private const val TAB_CHAT = 2

private const val DAY_MS = 86_400_000L

private val NavMargin = 12.dp

/** Room under every tab so the last row (and the chat input) clears the floating nav with a gap. */
private val TabPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = NavMargin + FloatingNavHeight + 24.dp)

/**
 * Four tabs over one [FloatingNav] (mockup: home, timeline, chat, memory). Home Node opens inside the memory tab. Each tab loads from its presenter on the DB thread and holds only the returned state.
 * Haku Plus (P9.4) opens over the tabs from Home's weekly chip or the Memory tab; back closes it.
 */
@Composable
fun MainShell(graph: AppGraph, activity: Activity) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var day by rememberSaveable { mutableStateOf<String?>(null) }
  var plusOpen by rememberSaveable { mutableStateOf(false) }
  var nodeOpen by rememberSaveable { mutableStateOf(false) }
  // the conversation lives as long as the shell, so switching tabs keeps it
  var chatTurns by remember { mutableStateOf(listOf<ChatTurn>()) }
  val shellScope = rememberCoroutineScope() // an answer still lands if the user switches tabs while it is being written
  BackHandler(enabled = plusOpen) { plusOpen = false }
  BackHandler(enabled = !plusOpen && nodeOpen && tab == NavItems.lastIndex) { nodeOpen = false }
  Box(Modifier.fillMaxSize()) {
    if (plusOpen) {
      PlusTab(graph, activity) { plusOpen = false }
      return@Box
    }
    val openDay = { d: String -> day = d; tab = TAB_TIMELINE }
    when (tab) {
      TAB_HOME -> HomeTab(graph, CaptureController.isEnabled(activity), openDay, { plusOpen = true })
      TAB_TIMELINE -> TimelineTab(graph, day) { day = it }
      TAB_CHAT -> ChatTab(graph, shellScope, chatTurns, { chatTurns = it }, openDay)
      else -> if (nodeOpen) HomeNodeScreen(onBack = { nodeOpen = false }, contentPadding = TabPadding)
      else MemoryTab(graph, { plusOpen = true }, { nodeOpen = true })
    }
    FloatingNav(NavItems, tab, { tab = it; nodeOpen = false }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = NavMargin))
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
  var today by remember { mutableStateOf(graph.today(System.currentTimeMillis())) }
  // stale after midnight if the app stayed open: recompute whenever it comes back to the foreground
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) today = graph.today(System.currentTimeMillis()) }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  val day = selected ?: today
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<TimelineUiState?>(null) }
  LaunchedEffect(day, today) { state = graph.safeIo { compose(day); timeline.load(day, today) } }
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
private fun ChatTab(graph: AppGraph, scope: CoroutineScope, turns: List<ChatTurn>, onTurns: (List<ChatTurn>) -> Unit, onOpenDay: (String) -> Unit) {
  var hasModel by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { hasModel = modelConfigOrNull(graph) != null }
  ChatScreen(
    turns,
    engineLabel = if (hasModel) "ในเครื่อง · Gemma" else "ในเครื่อง · ค้นจากบันทึก",
    onAsk = { q ->
      val asked = turns + ChatTurn(q, null)
      onTurns(asked)
      scope.launch {
        val answer = askHaku(graph, q) ?: ChatAnswerOutput(q, "ค้นบันทึกไม่สำเร็จ ลองใหม่อีกครั้ง", emptyList(), ChatAnswerSource.RETRIEVAL)
        onTurns(asked.dropLast(1) + ChatTurn(q, answer))
      }
    },
    onOpenDay = onOpenDay,
    contentPadding = TabPadding,
  )
}

/** Retrieval on the DB thread, then the downloaded model if there is one (loaded for this question, released after). */
private suspend fun askHaku(graph: AppGraph, question: String): ChatAnswerOutput? {
  val (presenter, retrieval) = graph.safeIo { chat to chat.retrieve(ChatQuestionInput(question, System.currentTimeMillis(), profile.load().style)) } ?: return null
  val config = modelConfigOrNull(graph) ?: return retrieval.fallback
  val model = LiteRtLocalModel()
  return try {
    val client = LocalModelClient(model)
    client.load(config)
    presenter.answer(retrieval, client)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.e("HakuShell", "chat model failed, showing the retrieval answer", e)
    retrieval.fallback
  } finally {
    withContext(NonCancellable) { runCatching { model.unload() } }
  }
}

/** External storage can be missing: that is "no model", the chat still answers from the diary. */
private suspend fun modelConfigOrNull(graph: AppGraph): ModelConfig? = try {
  graph.modelConfig()
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  Log.e("HakuShell", "model config unavailable", e)
  null
}

@Composable
private fun MemoryTab(graph: AppGraph, onOpenPlus: () -> Unit, onOpenHomeNode: () -> Unit) {
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
    onOpenHomeNode = onOpenHomeNode,
    contentPadding = TabPadding,
    // debug builds only (release DemoTools draws nothing)
    headerAction = { DemoSeedButton(graph, { scope.launch { graph.safeIo { transparency.load() }?.let { state = it } } }) },
  )
}
