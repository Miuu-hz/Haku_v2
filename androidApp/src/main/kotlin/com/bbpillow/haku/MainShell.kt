package com.bbpillow.haku

import com.bbpillow.haku.skill.MentionParser
import com.bbpillow.haku.skill.Mention
import com.bbpillow.haku.skill.SkillRecord
import com.bbpillow.haku.skill.BuiltInSkills
import com.bbpillow.haku.skill.McpResultSummary
import com.bbpillow.haku.skill.SkillActions
import com.bbpillow.haku.platform.agent.SkillTool
import com.bbpillow.haku.platform.agent.LiteRtAgentSession
import com.bbpillow.haku.thinking.AgentConfig
import com.bbpillow.haku.thinking.AgentInput
import com.bbpillow.haku.thinking.execute
import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.action.ActionStatus
import com.bbpillow.haku.action.CalendarAddArgs
import android.Manifest
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.thinking.summary.SummaryPromptBuilder
import com.bbpillow.haku.platform.BiometricLock
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.platform.signals.CalendarWriter
import com.bbpillow.haku.skill.McpServerRecord
import com.bbpillow.haku.surface.chat.AppointmentProposal
import com.bbpillow.haku.surface.chat.AppointmentStatus
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
import com.bbpillow.haku.surface.intent.IntentListScreen
import com.bbpillow.haku.surface.intent.IntentListUiState
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
import com.bbpillow.haku.surface.node.HomeNodeScreen
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.ModelKind
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.plus.PlusScreen
import com.bbpillow.haku.surface.settings.DownloadProgressInput
import com.bbpillow.haku.surface.settings.ExtensionsScreen
import com.bbpillow.haku.surface.settings.InstalledModelRecord
import com.bbpillow.haku.surface.settings.ModelManagerPresenter
import com.bbpillow.haku.surface.settings.ModelManagerScreen
import com.bbpillow.haku.surface.settings.ModelManagerUiState
import com.bbpillow.haku.surface.settings.ModelStatus
import com.bbpillow.haku.surface.settings.PrivacyNoticeScreen
import com.bbpillow.haku.surface.settings.SettingsScreen
import com.bbpillow.haku.surface.settings.SettingsUiState
import com.bbpillow.haku.surface.stats.StatsScreen
import com.bbpillow.haku.surface.stats.StatsUiState
import com.bbpillow.haku.surface.timeline.TimelineScreen
import com.bbpillow.haku.surface.timeline.TimelineUiState
import com.bbpillow.haku.thinking.GenerateEvent
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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

  fun update(then: (ProfileRecord) -> Unit = {}, block: ModelManagerPresenter.(List<InstalledModelRecord>) -> SettingsUiState) {
    scope.launch {
      val installed = withContext(Dispatchers.IO) { graph.installedModels() }
      graph.safeIo { modelManager.block(installed) to profile.load() }?.let { (ui, rec) ->
        state = ui
        onProfileChanged(rec)
        then(rec)
      }
    }
  }

  // P5.2: a brief is saved on only after the OS grants POST_NOTIFICATIONS; denied = it stays off
  val syncBriefs: (ProfileRecord) -> Unit = { com.bbpillow.haku.platform.notify.BriefWorker.sync(activity, it) }
  var askingBrief by remember { mutableStateOf<com.bbpillow.haku.surface.settings.BriefKind?>(null) }
  val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    val kind = askingBrief ?: return@rememberLauncherForActivityResult
    askingBrief = null
    if (granted) update(syncBriefs) { setBrief(kind, true, it) }
    else Toast.makeText(activity, "ต้องอนุญาตการแจ้งเตือนก่อน เปิดได้ในการตั้งค่าเครื่อง", Toast.LENGTH_LONG).show()
  }

  // P5.8 notification access lives in system settings: re-read it every time the user comes back from there
  fun listenerOn() = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)
  var notifOn by remember { mutableStateOf(listenerOn()) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) notifOn = listenerOn() }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  var page by remember { mutableStateOf(SettingsPage.MAIN) }
  BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
  when (page) {
    SettingsPage.EXTENSIONS -> return ExtensionsTab(graph) { page = SettingsPage.MAIN }
    SettingsPage.AUDIT -> return AuditTab(graph) { page = SettingsPage.MAIN }
    SettingsPage.PRIVACY -> return PrivacyNoticeScreen(onBack = { page = SettingsPage.MAIN }, contentPadding = TabPadding)
    SettingsPage.MAIN -> Unit
  }

  val s = state ?: return
  SettingsScreen(
    state = s,
    notificationAccessOn = notifOn,
    onOpenNotificationAccess = {
      // straight to Haku's own switch (API 30+); a skin without that page gets the full listener list
      val detail = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        android.content.ComponentName(activity, com.bbpillow.haku.platform.capture.HakuNotificationListener::class.java).flattenToString(),
      )
      try {
        activity.startActivity(detail)
      } catch (e: android.content.ActivityNotFoundException) {
        activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      }
    },
    onOpenExtensions = { page = SettingsPage.EXTENSIONS },
    onOpenAudit = { page = SettingsPage.AUDIT },
    onOpenPrivacyNotice = { page = SettingsPage.PRIVACY },
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
    onToggleBrief = { kind, on ->
      val needsAsk = on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
      if (needsAsk) {
        askingBrief = kind
        notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
      } else {
        update(syncBriefs) { setBrief(kind, on, it) }
      }
    },
    onPickBriefTime = { kind ->
      val current = com.bbpillow.haku.surface.settings.DailyBrief.parseMinute(
        if (kind == com.bbpillow.haku.surface.settings.BriefKind.MORNING) s.morningBriefTime else s.eveningBriefTime,
      ) ?: 0
      android.app.TimePickerDialog(activity, { _, h, m -> update(syncBriefs) { setBriefTime(kind, h * 60 + m, it) } }, current / 60, current % 60, true).show()
    },
    contentPadding = TabPadding,
  )
}

private enum class SettingsPage { MAIN, EXTENSIONS, AUDIT, PRIVACY }

@Composable
private fun ExtensionsTab(graph: AppGraph, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  var servers by remember { mutableStateOf<List<McpServerRecord>>(emptyList()) }
  var adding by remember { mutableStateOf(false) }
  var addError by remember { mutableStateOf<String?>(null) }
  var addedServer by remember { mutableStateOf<McpServerRecord?>(null) }
  // ponytail: built-ins always on for now, show switch disabled with "เปิดอยู่เสมอ"
  val skills = BuiltInSkills.ALL
  LaunchedEffect(Unit) { graph.safeIo { mcpServers.all() }?.let { servers = it } }
  ExtensionsScreen(
    skills = skills,
    servers = servers,
    adding = adding,
    addError = addError,
    addedServer = addedServer,
    onBack = onBack,
    onToggleTool = { url, tool, enabled ->
      scope.launch {
        graph.safeIo {
          mcpServers.setToolEnabled(url, tool, enabled)
          mcpServers.all()
        }?.let { servers = it }
      }
    },
    onRemoveServer = { url ->
      scope.launch {
        graph.safeIo {
          mcpServers.remove(url)
          mcpServers.all()
        }?.let { servers = it }
      }
    },
    onConnectServer = { url ->
      adding = true
      addError = null
      addedServer = null
      scope.launch {
        try {
          val described = withContext(Dispatchers.IO) {
            graph.mcpClient.describe(url, System.currentTimeMillis())
          }
          graph.safeIo {
            mcpServers.upsert(described)
            mcpServers.all()
          }?.let { servers = it }
          addedServer = described
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          addError = e.message ?: "เชื่อมต่อบริการไม่สำเร็จ"
        } finally {
          adding = false
        }
      }
    },
    onDismissAdd = {
      addError = null
      addedServer = null
    },
    contentPadding = TabPadding,
  )
}

/** P4.1 audit log + emergency stop. ponytail: the stop flag is in memory (gate), it resets on process death. */
@Composable
private fun AuditTab(graph: AppGraph, onBack: () -> Unit) {
  val gate = graph.actionGate
  val stopped by gate.stopped.collectAsState()
  var records by remember { mutableStateOf<List<com.bbpillow.haku.action.AuditRecord>>(emptyList()) }
  LaunchedEffect(stopped) { graph.safeIo { audit.recent(AUDIT_PAGE) }?.let { records = it } }
  com.bbpillow.haku.surface.action.AuditScreen(
    records = records,
    stopped = stopped,
    onToggleStop = { stop -> if (stop) gate.stopAll() else gate.resume() },
    onBack = onBack,
    contentPadding = TabPadding,
  )
}

private const val AUDIT_PAGE = 200

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
        // the vectors are derived from the user's diary by this model: they go with it (PDPA review stage 3, finding 3)
        if (choice?.kind == ModelKind.EMBEDDING) graph.safeIo { vectors.clear() }
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
  val context = LocalContext.current
  var modelLabel by remember { mutableStateOf(ModelManagerPresenter.NO_MODEL_LABEL) }
  // 5ข: models load while the user types, and go when the tab or the app is left (or 60 s pass with no question)
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_START) graph.warmth.open()
      if (e == Lifecycle.Event.ON_STOP) graph.warmth.stop()
    }
    lifecycle.addObserver(observer) // replays ON_START when the app is already in front
    onDispose { lifecycle.removeObserver(observer); graph.warmth.stop() }
  }
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

  // the calendar insert finishes after recomposition; read the latest turns so a question asked meanwhile is kept
  val latestTurns by rememberUpdatedState(turns)
  fun updateAppointment(index: Int, transform: (AppointmentProposal) -> AppointmentProposal) {
    val turn = latestTurns.getOrNull(index) ?: return
    val answer = turn.answer ?: return
    val appointment = answer.appointment ?: return
    val updated = latestTurns.toMutableList()
    updated[index] = turn.copy(answer = answer.copy(appointment = transform(appointment)))
    onTurns(updated)
  }

  fun saveAppointment(index: Int) {
    val turn = turns.getOrNull(index) ?: return
    val appointment = turn.answer?.appointment ?: return
    if (appointment.status == AppointmentStatus.SAVING || appointment.status == AppointmentStatus.ADDED) return
    updateAppointment(index) { it.copy(status = AppointmentStatus.SAVING) }
    scope.launch {
      // P4.1: through the gate like every other action; the tap on add is the user's confirm. confirmAction writes the diary note.
      val done = (graph.actionGate.submit(CalendarAddArgs.fromProposal(appointment)) as? ActionDecision.AwaitingUser)
        ?.let { graph.confirmAction(it.proposal.id) }?.status == ActionStatus.DONE
      if (done) {
        updateAppointment(index) { it.copy(status = AppointmentStatus.ADDED) }
      } else {
        updateAppointment(index) { it.copy(status = AppointmentStatus.FAILED) }
      }
    }
  }

  var pendingAppointmentIndex by remember { mutableStateOf<Int?>(null) }
  val calendarPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
  ) {
    val index = pendingAppointmentIndex ?: return@rememberLauncherForActivityResult
    pendingAppointmentIndex = null
    if (CalendarWriter.canWrite(context)) {
      saveAppointment(index)
    } else {
      updateAppointment(index) { it.copy(status = AppointmentStatus.NEEDS_PERMISSION) }
    }
  }

  val proposals by graph.actionGate.proposals.collectAsState()
  var mentionSkills by remember { mutableStateOf(BuiltInSkills.ALL) }
  LaunchedEffect(Unit) { mentionSkills = availableSkills(graph) } // re-read each time the chat tab opens
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
    onAddAppointment = { index ->
      val turn = turns.getOrNull(index) ?: return@ChatScreen
      val appointment = turn.answer?.appointment ?: return@ChatScreen
      if (appointment.status == AppointmentStatus.SAVING || appointment.status == AppointmentStatus.ADDED) return@ChatScreen
      if (!CalendarWriter.canWrite(context)) {
        pendingAppointmentIndex = index
        calendarPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
      } else {
        saveAppointment(index)
      }
    },
    onDismissAppointment = { index ->
      updateAppointment(index) { it.copy(status = AppointmentStatus.DISMISSED) }
    },
    actions = proposals.associateBy { it.id },
    onConfirmAction = { id ->
      scope.launch {
        val record = graph.confirmAction(id) ?: return@launch
        if (record.request.action != SkillActions.MCP_ACTION || record.status != ActionStatus.DONE) return@launch
        val question = latestTurns.firstOrNull { it.answer?.actionId == id }?.answer?.question ?: return@launch
        val text = summarizeMcpResult(graph, question, record)
        // re-read: the summary takes seconds and a question asked meanwhile must be kept
        val i = latestTurns.indexOfFirst { it.answer?.actionId == id }.takeIf { it >= 0 } ?: return@launch
        val turn = latestTurns[i]
        onTurns(latestTurns.toMutableList().also { it[i] = turn.copy(answer = turn.answer?.copy(text = text)) })
      }
    },
    onDismissAction = { id -> graph.actionGate.dismiss(id) },
    mentionSkills = mentionSkills,
    contentPadding = TabPadding,
  )
}

/**
 * Retrieval on the DB thread, then the downloaded model if there is one. The engine comes from [AppGraph.warmth] when the
 * chat tab warmed it (the per-question load/unload below then only moves a reference); otherwise this question warms it.
 * internal: the debug AskDebugReceiver ASK_FULL action drives this exact path over adb.
 */
internal suspend fun askHaku(graph: AppGraph, question: String): ChatAnswerOutput? = graph.warmth.use { askOnce(graph, question) }

private suspend fun askOnce(graph: AppGraph, question: String): ChatAnswerOutput? {
  val tz = graph.tzOffsetMinutes
  // Week 2: "/skill …" or "@service …" names what to use; a mention only skips the picker, the gate card still asks
  val skills = availableSkills(graph)
  when (val m = MentionParser.parse(question, skills)) {
    is Mention.Skill -> return runSkill(graph, question, m.rest.ifBlank { question }, listOf(m.skill))
    is Mention.Service -> return runSkill(graph, question, m.rest.ifBlank { question }, m.tools.take(MAX_SERVICE_TOOLS))
    is Mention.NotFound -> {
      val what = if (m.trigger == MentionParser.SERVICE) "บริการ" else "คำสั่ง"
      return ChatAnswerOutput(question, "ไม่พบ$what ${m.trigger}${m.name} ลองพิมพ์ ${m.trigger} เพื่อดูรายการที่มี", emptyList(), ChatAnswerSource.MODEL)
    }
    null -> Unit
  }
  val appointmentAnswer = graph.safeIo { appointmentCommand.tryPropose(question, System.currentTimeMillis(), tz) }
  val proposal = appointmentAnswer?.appointment
  if (proposal != null) {
    val conflict = withContext(Dispatchers.IO) {
      runCatching { CalendarWriter.conflictLine(graph.app, proposal.startUtcMs, proposal.endUtcMs, tz, proposal.allDay) }.getOrNull()
    }
    return appointmentAnswer.copy(appointment = proposal.copy(conflict = conflict ?: proposal.conflict))
  }
  graph.safeIo { intentCommand.tryHandle(question, System.currentTimeMillis()) }?.let { return it } // P5.3 "ถ้า … ให้เตือน …"
  graph.pickSkill(question, skills)?.let { if (BuildConfig.DEBUG) Log.d("HakuSkill", "picked ${it.name}"); return runSkill(graph, question, question, listOf(it)) }
  val semantic = graph.questionSemantic(question) // words-only while the embedder is cold or absent
  val (presenter, retrieval) = graph.safeIo { composeRecent(System.currentTimeMillis()); chat to chat.retrieve(ChatQuestionInput(question, System.currentTimeMillis(), profile.load().style), semantic) } ?: return null
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

/** Built-ins plus the MCP tools the user turned on (Settings → ความสามารถเสริม). */
private suspend fun availableSkills(graph: AppGraph): List<SkillRecord> =
  BuiltInSkills.ALL + (graph.safeIo { mcpServers.all().flatMap { it.tools }.filter { it.enabled } } ?: emptyList())

private const val MAX_SERVICE_TOOLS = 3

/**
 * One skill turn: the model gets only [skills] as tools (built from their slots) and the skills' short instructions.
 * A call becomes a gate card under the answer; nothing runs or leaves the phone until the user confirms it.
 */
private suspend fun runSkill(graph: AppGraph, question: String, text: String, skills: List<SkillRecord>): ChatAnswerOutput {
  fun answer(t: String, actionId: String? = null) = ChatAnswerOutput(question, t, emptyList(), ChatAnswerSource.MODEL, actionId = actionId)
  val config = modelConfigOrNull(graph) ?: return answer("ต้องมี AI ในเครื่องก่อน จึงจะใช้ ${skills.joinToString { it.title }} ได้")
  var proposal: ActionProposalRecord? = null
  val tools = skills.map { s -> SkillTool(s, graph.actionGate, graph.tzOffsetMinutes) { proposal = it } }
  val session = LiteRtAgentSession(emptyList(), tools)
  // the user's ending (onboarding) as in chat; without it the model falls back to a generic ผม/ดิฉัน … ครับ/ค่ะ
  val particle = graph.safeIo { profile.load().style.particle } ?: EndingParticle.NONE
  val system = "คุณคือ Haku ตอบภาษาไทยสั้นๆ ใช้ tool ที่ให้ไว้เท่านั้น\n" + SummaryPromptBuilder.particleRule(particle) +
    skills.joinToString("") { "\n${it.title}: ${it.instructions}" }
  return try {
    session.start(AgentConfig(config, system))
    val out = session.execute(AgentInput(text))
    val p = proposal
    when {
      p != null && p.status == ActionStatus.PENDING -> answer("ดูรายละเอียดในการ์ด แล้วกดยืนยันถ้าถูกต้อง", p.id)
      p != null -> answer(p.message ?: out.text.trim(), p.id)
      else -> answer(out.text.trim().ifBlank { "ยังทำไม่ได้ ลองบอกรายละเอียดเพิ่มอีกนิด" })
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.e("HakuShell", "skill turn failed", e)
    answer("ใช้ ${skills.joinToString { it.title }} ไม่สำเร็จ ลองใหม่อีกครั้ง")
  } finally {
    withContext(NonCancellable) { runCatching { session.close() } }
  }
}

/**
 * Task T: the confirmed `mcp.call` result → a short Thai answer for the bubble. The result is untrusted external text,
 * so this is a plain generate with no tools; no model, a failure or a blank reply give [McpResultSummary.FALLBACK].
 * internal: the debug McpDebugReceiver MCP_CONFIRM drives it over adb.
 */
internal suspend fun summarizeMcpResult(graph: AppGraph, question: String, record: ActionProposalRecord): String {
  val particle = graph.safeIo { profile.load().style.particle } ?: EndingParticle.NONE
  val raw = record.message?.takeIf { it.isNotBlank() } ?: return McpResultSummary.FALLBACK
  val config = modelConfigOrNull(graph) ?: return McpResultSummary.FALLBACK
  val prompt = McpResultSummary.prompt(question, record.request.title, raw, particle)
  return graph.warmth.use {
    val model = LiteRtLocalModel()
    try {
      val client = LocalModelClient(model)
      client.load(config)
      val out = client.generate(GenerateInput(prompt)).filterIsInstance<GenerateEvent.Finished>().first().output.text
      McpResultSummary.finish(out, particle)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e("HakuShell", "mcp summary failed, showing the fallback line", e)
      McpResultSummary.FALLBACK
    } finally {
      withContext(NonCancellable) { runCatching { model.unload() } }
    }
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
  var intentsOpen by rememberSaveable { mutableStateOf(false) } // P5.3 sub-screen of this tab
  if (intentsOpen) {
    var list by remember { mutableStateOf<IntentListUiState?>(null) }
    LaunchedEffect(Unit) { list = graph.safeIo { intentList.load() } }
    BackHandler { intentsOpen = false }
    list?.let { l ->
      IntentListScreen(l, onCancel = { item -> scope.launch { graph.safeIo { intentList.cancel(item.id) }?.let { list = it } } }, onBack = { intentsOpen = false }, contentPadding = TabPadding)
    }
    return
  }
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
    onOpenIntentions = { intentsOpen = true },
    contentPadding = TabPadding,
    // debug builds only (release DemoTools draws nothing)
    headerAction = { DemoSeedButton(graph, { scope.launch { graph.safeIo { transparency.load() }?.let { state = it } } }) },
  )
}
