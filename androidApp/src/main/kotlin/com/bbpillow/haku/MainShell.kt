package com.bbpillow.haku

import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.platform.BiometricLock
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.chat.ChatAnswerOutput
import com.bbpillow.haku.surface.chat.ChatAnswerSource
import com.bbpillow.haku.surface.chat.ChatQuestionInput
import com.bbpillow.haku.surface.chat.ChatScreen
import com.bbpillow.haku.surface.chat.ChatTurn
import com.bbpillow.haku.surface.components.FloatingNav
import com.bbpillow.haku.surface.components.FloatingNavHeight
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.NavItem
import com.bbpillow.haku.surface.home.HomeBriefScreen
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
import com.bbpillow.haku.surface.node.HomeNodeScreen
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.plus.PlusScreen
import com.bbpillow.haku.surface.settings.DownloadProgressInput
import com.bbpillow.haku.surface.settings.InstalledModelRecord
import com.bbpillow.haku.surface.settings.ModelManagerPresenter
import com.bbpillow.haku.surface.settings.ModelManagerScreen
import com.bbpillow.haku.surface.settings.ModelManagerUiState
import com.bbpillow.haku.surface.settings.ModelStatus
import com.bbpillow.haku.surface.settings.SettingsScreen
import com.bbpillow.haku.surface.settings.SettingsUiState
import com.bbpillow.haku.surface.stats.StatsScreen
import com.bbpillow.haku.surface.stats.StatsUiState
import com.bbpillow.haku.surface.timeline.TimelineScreen
import com.bbpillow.haku.surface.timeline.TimelineUiState
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Four tabs over one [FloatingNav] (mockup: home, timeline, chat, memory). Home Node, Settings, Model Manager, and
 * Haku Plus open as sub-screens without a nav library; back closes the top-most sub-screen.
 */
@Composable
fun MainShell(
  graph: AppGraph,
  activity: FragmentActivity,
  onProfileChanged: (ProfileRecord) -> Unit = {},
) {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var day by rememberSaveable { mutableStateOf<String?>(null) }
  var plusOpen by rememberSaveable { mutableStateOf(false) }
  var nodeOpen by rememberSaveable { mutableStateOf(false) }
  var settingsOpen by rememberSaveable { mutableStateOf(false) }
  var modelsOpen by rememberSaveable { mutableStateOf(false) }
  var statsOpen by rememberSaveable { mutableStateOf(false) }
  // the conversation lives as long as the shell, so switching tabs keeps it
  var chatTurns by remember { mutableStateOf(listOf<ChatTurn>()) }
  val shellScope = rememberCoroutineScope() // an answer still lands if the user switches tabs while it is being written
  val dl by graph.downloader.state.collectAsState()
  val imp by graph.importer.state.collectAsState()
  LaunchedEffect(dl?.done, imp?.done) {
    val choice = dl?.takeIf { it.done }?.choice
    val imported = imp?.takeIf { it.done }?.fileName
    if (choice == null && imported == null) return@LaunchedEffect
    val installed = withContext(Dispatchers.IO) { graph.installedModels() }
    graph.safeIo {
      if (choice != null) modelManager.activateIfNoneReady(ActiveModel.Catalog(choice), installed)
      if (imported != null) modelManager.activateIfNoneReady(ActiveModel.Imported(imported), installed)
      profile.load()
    }?.let(onProfileChanged)
  }
  BackHandler(enabled = plusOpen) { plusOpen = false }
  BackHandler(enabled = !plusOpen && modelsOpen) { modelsOpen = false }
  BackHandler(enabled = !plusOpen && !modelsOpen && settingsOpen) { settingsOpen = false }
  BackHandler(enabled = !plusOpen && !modelsOpen && !settingsOpen && nodeOpen && tab == NavItems.lastIndex) { nodeOpen = false }
  BackHandler(enabled = !plusOpen && !modelsOpen && !settingsOpen && statsOpen && tab == TAB_HOME) { statsOpen = false }
  Box(Modifier.fillMaxSize()) {
    if (plusOpen) {
      PlusTab(graph, activity) { plusOpen = false }
      return@Box
    }
    if (modelsOpen) {
      ModelManagerTab(graph, activity, onProfileChanged) { modelsOpen = false }
      return@Box
    }
    if (settingsOpen) {
      SettingsTab(graph, activity, { modelsOpen = true }, onProfileChanged) { settingsOpen = false }
      return@Box
    }
    val openDay = { d: String -> day = d; tab = TAB_TIMELINE }
    when (tab) {
      TAB_HOME -> if (statsOpen) StatsTab(graph, { plusOpen = true }) { statsOpen = false }
      else HomeTab(graph, CaptureController.isEnabled(activity), openDay, { plusOpen = true }) { statsOpen = true }
      TAB_TIMELINE -> TimelineTab(graph, day) { day = it }
      TAB_CHAT -> ChatTab(graph, shellScope, chatTurns, { chatTurns = it }, openDay, { modelsOpen = true })
      else -> if (nodeOpen) HomeNodeScreen(onBack = { nodeOpen = false }, contentPadding = TabPadding)
      else MemoryTab(graph, { plusOpen = true }, { nodeOpen = true }, { settingsOpen = true })
    }
    FloatingNav(NavItems, tab, { tab = it; nodeOpen = false; statsOpen = false }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = NavMargin))
  }
}

@Composable
private fun PlusTab(graph: AppGraph, activity: FragmentActivity, onClose: () -> Unit) {
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
private fun SettingsTab(
  graph: AppGraph,
  activity: FragmentActivity,
  onOpenModels: () -> Unit,
  onProfileChanged: (ProfileRecord) -> Unit,
  onClose: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<SettingsUiState?>(null) }
  val dl by graph.downloader.state.collectAsState()
  val imp by graph.importer.state.collectAsState()

  LaunchedEffect(dl?.done, imp?.done) {
    val choice = dl?.takeIf { it.done }?.choice
    val imported = imp?.takeIf { it.done }?.fileName
    val installed = withContext(Dispatchers.IO) { graph.installedModels() }
    val loaded = graph.safeIo {
      if (choice != null) modelManager.activateIfNoneReady(ActiveModel.Catalog(choice), installed)
      if (imported != null) modelManager.activateIfNoneReady(ActiveModel.Imported(imported), installed)
      modelManager.loadSettings(installed)
    } ?: return@LaunchedEffect
    state = state?.copy(activeModelLabel = loaded.activeModelLabel) ?: loaded
  }

  fun update(block: ModelManagerPresenter.(List<InstalledModelRecord>) -> SettingsUiState) {
    scope.launch {
      val installed = withContext(Dispatchers.IO) { graph.installedModels() }
      graph.safeIo { modelManager.block(installed) to profile.load() }?.let { (ui, rec) ->
        state = ui
        onProfileChanged(rec)
      }
    }
  }

  val s = state ?: return
  SettingsScreen(
    state = s,
    onBack = onClose,
    onOpenModels = onOpenModels,
    onParticle = { p -> update { setParticle(p, it) } },
    onAddress = { a -> update { setAddress(a, it) } },
    onNickname = { n ->
      val nextStyle = s.style.copy(nickname = n.take(ProfileStore.MAX_NICKNAME))
      state = s.copy(style = nextStyle, stylePreview = OnboardingPresenter.preview(nextStyle))
      scope.launch {
        graph.safeIo {
          modelManager.setNickname(n, emptyList())
          profile.load()
        }?.let(onProfileChanged)
      }
    },
    onToggleLock = { enabled ->
      if (enabled) {
        if (!BiometricLock.available(activity)) {
          Toast.makeText(activity, "ตั้งล็อกหน้าจอหรือสแกนนิ้วในการตั้งค่าเครื่องก่อน", Toast.LENGTH_LONG).show()
        } else {
          BiometricLock.prompt(activity, "เปิดล็อก Haku") { ok -> if (ok) update { setLock(true, it) } }
        }
      } else {
        update { setLock(false, it) }
      }
    },
    contentPadding = TabPadding,
  )
}

@Composable
private fun ModelManagerTab(
  graph: AppGraph,
  activity: FragmentActivity,
  onProfileChanged: (ProfileRecord) -> Unit,
  onClose: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val dl by graph.downloader.state.collectAsState()
  val imp by graph.importer.state.collectAsState()
  var state by remember { mutableStateOf<ModelManagerUiState?>(null) }
  var message by remember { mutableStateOf<String?>(null) }

  suspend fun refresh(activateChoice: ModelChoice? = null, activateImport: String? = null) {
    val ram = graph.ramGb()
    val installed = withContext(Dispatchers.IO) { graph.installedModels() }
    val currentDl = graph.downloader.state.value
    val dlInput = currentDl?.let { DownloadProgressInput(it.choice, it.fraction, it.paused, it.done, it.error) }
    val currentImp = graph.importer.state.value
    graph.safeIo {
      if (activateChoice != null) modelManager.activateIfNoneReady(ActiveModel.Catalog(activateChoice), installed)
      if (activateImport != null) modelManager.activateIfNoneReady(ActiveModel.Imported(activateImport), installed)
      modelManager.load(
        ramGb = ram,
        installed = installed,
        download = dlInput,
        importingFileName = currentImp?.takeIf { !it.done && it.error == null }?.fileName,
        importFraction = currentImp?.takeIf { !it.done && it.error == null }?.fraction ?: 0f,
        message = message ?: currentImp?.error ?: currentDl?.error,
      ) to profile.load()
    }?.let { (ui, rec) ->
      state = ui
      onProfileChanged(rec)
    }
  }

  LaunchedEffect(dl?.choice, dl?.paused, dl?.done, dl?.error) {
    val current = dl
    if (current?.error != null) {
      message = current.error
      Toast.makeText(activity, "ดาวน์โหลดหยุด: ${current.error}", Toast.LENGTH_LONG).show()
      graph.downloader.clearError()
    }
    refresh(activateChoice = current?.takeIf { it.done }?.choice)
  }

  LaunchedEffect(dl?.fraction) {
    val current = dl ?: return@LaunchedEffect
    if (current.done || current.error != null) return@LaunchedEffect
    val s = state ?: return@LaunchedEffect
    state = s.copy(
      models = s.models.map { card ->
        if (card.choice == current.choice && card.status == ModelStatus.DOWNLOADING) {
          card.copy(downloadFraction = current.fraction, downloadPaused = current.paused)
        } else {
          card
        }
      },
    )
  }

  LaunchedEffect(imp?.fileName, imp?.done, imp?.error) {
    val current = imp ?: return@LaunchedEffect
    if (current.error != null) {
      message = current.error
      Toast.makeText(activity, current.error, Toast.LENGTH_LONG).show()
      graph.importer.clearError()
    }
    refresh(activateImport = current.takeIf { it.done }?.fileName)
  }

  LaunchedEffect(imp?.fraction) {
    val current = imp ?: return@LaunchedEffect
    if (current.done || current.error != null) return@LaunchedEffect
    state = state?.copy(importingFileName = current.fileName, importFraction = current.fraction)
  }

  fun clearErrors() {
    message = null
    graph.downloader.clearError()
    graph.importer.clearError()
  }

  val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      clearErrors()
      graph.importer.import(uri) { importedName ->
        scope.launch { refresh(activateImport = importedName) }
      }
    }
  }

  val s = state ?: return
  ModelManagerScreen(
    state = s,
    onBack = onClose,
    onDownload = { choice ->
      clearErrors()
      if (!graph.downloader.start(choice)) {
        message = ModelManagerPresenter.BUSY_DOWNLOAD_TEXT
        state = s.copy(message = ModelManagerPresenter.BUSY_DOWNLOAD_TEXT)
        Toast.makeText(activity, ModelManagerPresenter.BUSY_DOWNLOAD_TEXT, Toast.LENGTH_SHORT).show()
      } else {
        scope.launch { refresh() }
      }
    },
    onPauseResume = { choice, pause ->
      if (pause) {
        graph.downloader.pause()
      } else {
        clearErrors()
        if (!graph.downloader.start(choice)) {
          message = ModelManagerPresenter.BUSY_DOWNLOAD_TEXT
          state = s.copy(message = ModelManagerPresenter.BUSY_DOWNLOAD_TEXT)
          Toast.makeText(activity, ModelManagerPresenter.BUSY_DOWNLOAD_TEXT, Toast.LENGTH_SHORT).show()
        }
      }
    },
    onSetActive = { target ->
      clearErrors()
      scope.launch {
        val ram = graph.ramGb()
        val installed = withContext(Dispatchers.IO) { graph.installedModels() }
        val currentDl = graph.downloader.state.value
        val dlInput = currentDl?.let { DownloadProgressInput(it.choice, it.fraction, it.paused, it.done, it.error) }
        graph.safeIo {
          modelManager.setActive(target, ram, installed, dlInput) to profile.load()
        }?.let { (ui, rec) ->
          state = ui
          onProfileChanged(rec)
        }
      }
    },
    onDelete = { card ->
      clearErrors()
      scope.launch {
        val choice = card.choice
        if (choice != null) graph.downloader.delete(choice) else graph.importer.delete(card.fileName)
        val ram = graph.ramGb()
        val installed = withContext(Dispatchers.IO) { graph.installedModels() }
        val currentDl = graph.downloader.state.value
        val dlInput = currentDl?.let { DownloadProgressInput(it.choice, it.fraction, it.paused, it.done, it.error) }
        graph.safeIo {
          modelManager.deleteModel(card.ref, ram, installed, dlInput) to profile.load()
        }?.let { (ui, rec) ->
          state = ui
          onProfileChanged(rec)
        }
      }
    },
    onImport = {
      clearErrors()
      importPicker.launch(arrayOf("*/*"))
    },
    contentPadding = TabPadding,
  )
}

@Composable
private fun HomeTab(graph: AppGraph, isCapturing: Boolean, onOpenDay: (String) -> Unit, onOpenPlus: () -> Unit, onOpenStats: () -> Unit) {
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
    onOpenStats = onOpenStats,
  )
}

@Composable
private fun StatsTab(graph: AppGraph, onOpenPlus: () -> Unit, onBack: () -> Unit) {
  var kind by rememberSaveable { mutableStateOf(PeriodKind.WEEK) }
  var state by remember { mutableStateOf<StatsUiState?>(null) }
  val entitlements by graph.billing.entitlements.collectAsState()
  LaunchedEffect(kind, entitlements.plan) {
    val now = System.currentTimeMillis()
    state = graph.safeIo { home.stats.load(kind, now, profile.load().style) }
  }
  val s = state ?: return
  StatsScreen(s, onSelect = { kind = it }, onOpenPlus = onOpenPlus, onBack = onBack, contentPadding = TabPadding)
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
private fun ChatTab(
  graph: AppGraph,
  scope: CoroutineScope,
  turns: List<ChatTurn>,
  onTurns: (List<ChatTurn>) -> Unit,
  onOpenDay: (String) -> Unit,
  onOpenModels: () -> Unit,
) {
  var modelLabel by remember { mutableStateOf(ModelManagerPresenter.NO_MODEL_LABEL) }
  LaunchedEffect(Unit) { graph.warmEmbedder() }
  val dl by graph.downloader.state.collectAsState()
  val imp by graph.importer.state.collectAsState()
  LaunchedEffect(dl?.done, imp?.done) {
    val choice = dl?.takeIf { it.done }?.choice
    val imported = imp?.takeIf { it.done }?.fileName
    val installed = withContext(Dispatchers.IO) { graph.installedModels() }
    modelLabel = graph.safeIo {
      if (choice != null) modelManager.activateIfNoneReady(ActiveModel.Catalog(choice), installed)
      if (imported != null) modelManager.activateIfNoneReady(ActiveModel.Imported(imported), installed)
      modelManager.activeModelLabel(installed)
    } ?: ModelManagerPresenter.NO_MODEL_LABEL
  }
  ChatScreen(
    turns,
    engineLabel = modelLabel,
    onAsk = { q ->
      val asked = turns + ChatTurn(q, null)
      onTurns(asked)
      scope.launch {
        val answer = askHaku(graph, q) ?: ChatAnswerOutput(q, "ค้นบันทึกไม่สำเร็จ ลองใหม่อีกครั้ง", emptyList(), ChatAnswerSource.RETRIEVAL)
        onTurns(asked.dropLast(1) + ChatTurn(q, answer))
      }
    },
    onOpenDay = onOpenDay,
    onOpenModels = onOpenModels,
    contentPadding = TabPadding,
  )
}

/** Retrieval on the DB thread, then the downloaded model if there is one (loaded for this question, released after). */
private suspend fun askHaku(graph: AppGraph, question: String): ChatAnswerOutput? {
  val semantic = graph.questionSemantic(question) // words-only while the embedder is cold or absent
  val (presenter, retrieval) = graph.safeIo { chat to chat.retrieve(ChatQuestionInput(question, System.currentTimeMillis(), profile.load().style), semantic) } ?: return null
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
private fun MemoryTab(
  graph: AppGraph,
  onOpenPlus: () -> Unit,
  onOpenHomeNode: () -> Unit,
  onOpenSettings: () -> Unit,
) {
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
    onOpenSettings = onOpenSettings,
    contentPadding = TabPadding,
    // debug builds only (release DemoTools draws nothing)
    headerAction = { DemoSeedButton(graph, { scope.launch { graph.safeIo { transparency.load() }?.let { state = it } } }) },
  )
}
