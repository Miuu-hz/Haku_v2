package com.bbpillow.haku

import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionStatus
import com.bbpillow.haku.action.AuditStore
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.action.DefaultActionGate
import com.bbpillow.haku.memory.index.MemoryIndexer
import com.bbpillow.haku.memory.index.RebuildReport
import com.bbpillow.haku.platform.widget.HakuWidget
import com.bbpillow.haku.surface.note.QuickNoteInput
import com.bbpillow.haku.platform.mcp.KtorMcpClient
import com.bbpillow.haku.platform.mcp.McpActionHandler
import com.bbpillow.haku.platform.signals.CalendarActionHandler
import com.bbpillow.haku.skill.McpServerStore
import java.util.UUID
import kotlinx.coroutines.launch
import android.app.ActivityManager
import android.app.Application
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.PlaceGuessOutput
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceStore
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryLedgerStore
import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.thaiSynonyms
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.VectorStore
import com.bbpillow.haku.network.ConnectionStore
import com.bbpillow.haku.platform.DataKeys
import com.bbpillow.haku.platform.IcuWordSegmenter
import com.bbpillow.haku.platform.LiteRtTextEmbedder
import com.bbpillow.haku.platform.LlmSettingsPrefs
import com.bbpillow.haku.platform.SharedEngines
import com.google.ai.edge.litertlm.Engine
import com.bbpillow.haku.platform.ModelDownloader
import com.bbpillow.haku.platform.ModelImporter
import com.bbpillow.haku.platform.billing.PlayBilling
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.platform.notify.IntentNotifier
import com.bbpillow.haku.surface.chat.AppointmentCommand
import com.bbpillow.haku.surface.chat.IntentCommand
import com.bbpillow.haku.surface.intent.IntentListPresenter
import com.bbpillow.haku.thinking.intent.IntentFireOutput
import com.bbpillow.haku.thinking.intent.IntentService
import com.bbpillow.haku.surface.chat.ChatPresenter
import com.bbpillow.haku.surface.chat.NowStateBuilder
import com.bbpillow.haku.surface.home.HomeBriefPresenter
import com.bbpillow.haku.surface.chat.ChatHistoryStore
import com.bbpillow.haku.surface.memory.ForgetEverything
import com.bbpillow.haku.surface.memory.TransparencyPresenter
import com.bbpillow.haku.surface.note.QuickNote
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.plus.EntitlementStore
import com.bbpillow.haku.surface.settings.InstalledModelRecord
import com.bbpillow.haku.surface.settings.LlmMetrics
import com.bbpillow.haku.surface.settings.LlmSettingsPresenter
import com.bbpillow.haku.surface.settings.ModelManagerPresenter
import com.bbpillow.haku.surface.timeline.TimelinePresenter
import com.bbpillow.haku.thinking.LlmDefaults
import com.bbpillow.haku.thinking.LlmSettings
import com.bbpillow.haku.thinking.ModelLoadPolicy
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.ModelWarmth
import com.bbpillow.haku.thinking.context.SemanticTier
import com.bbpillow.haku.thinking.context.VectorIndexOutput
import com.bbpillow.haku.thinking.context.VectorIndexer
import com.bbpillow.haku.thinking.context.VectorSemanticTier
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.io.File
import java.util.TimeZone
import kotlin.math.ceil
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/**
 * Manual DI (P3.6): one SQLite connection (CaptureController's, `noBackupFilesDir/haku.db`), the stores over it and the
 * presenters, each built once. Stores and memory files are not thread-safe, so every call goes through [io], which runs
 * on the capture thread. Built in [HakuApplication.onCreate]; nothing opens until the first [io] call.
 * ponytail: tz offset fixed per process (Thailand has no DST); pass it per call if travel across zones matters.
 */
class AppGraph(val app: Application) {
  private val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

  /** Outlives activities (fold/unfold, backgrounding) so the model download keeps going. */
  val scope = CoroutineScope(SupervisorJob())

  /** P9.4 Haku Plus. Built on first use (not in worker processes that never ask); the file goes through the capture thread. */
  val billing by lazy {
    PlayBilling(app, EntitlementStore(DataKeys.files, app.filesDir.resolve("entitlement.txt").path.toPath()), CaptureController.dispatcher, scope)
  }

  /** What the stats gate asks; follows [PlayBilling.entitlements], so a purchase or an expired grace shows on the next load. */
  val entitlements = object : Entitlements { override val plan: Plan get() = billing.entitlements.value.plan }

  private val modelsDir by lazy { requireNotNull(app.getExternalFilesDir("models")) { "no app-specific storage" } }
  /** Every connection it opens goes into the E3 connection log (Transparency screen). */
  val downloader by lazy { ModelDownloader(modelsDir, scope) { record -> io { connections.add(record) } } }
  val importer by lazy { ModelImporter(app, modelsDir, scope) }
  val mcpClient by lazy { KtorMcpClient { record -> io { connections.add(record) } } }

  /** The stores and presenters. Touch only inside [io]: their constructors create tables. */
  inner class Data {
    private val db = CaptureController.connection(app)
    /** D11: seal any pre-D11 plaintext files once, before the stores below read them. */
    private val legacySealed = DataKeys.sealLegacyFiles(app)
    val profile = ProfileStore(DataKeys.files, app.filesDir.resolve("profile.txt").path.toPath())
    /** MCP servers are settings (D11 encrypted), not personal memory: untouched by ForgetEverything. */
    val mcpServers = McpServerStore(DataKeys.files, app.filesDir.resolve("mcp").path.toPath())
    val events = CaptureEventStore(db)
    val places = PlaceStore(db)
    private val pipeline = TextPipeline(IcuWordSegmenter())
    private val index = SearchIndexStore(db, pipeline)
    /** ชิ้น ค embeddings: same no-backup DB as the FTS index, deleted with each line and by forget-everything. */
    val vectors = VectorStore(db)
    val memory = IndexedMemoryRepository(MemoryStore(DataKeys.files, app.filesDir.resolve("memory").path.toPath()), index, vectors)
    val ledger = DiaryLedgerStore(db)
    /** E3: connections the app opened, 30 days. ponytail: Play Billing is IPC to the Play Store app, which connects for us, so not logged. */
    val connections = ConnectionStore(db)
    /** P4.1 who asked for which action and what happened (90 days), shown on the audit screen. */
    val audit = AuditStore(db)
    /** P3.8 saved chats: 7 days unless pinned, gone with forget-everything. */
    val chatHistory = ChatHistoryStore(db)
    val diary = DiaryComposer(events, places, memory, ledger)
    /** P2.7 evening summaries, one file per local day (written by NightlyWorker, read by Home). */
    val summaries = EveningSummaryStore(DataKeys.files, app.filesDir.resolve("summaries").path.toPath())
    val home = HomeBriefPresenter(diary, events, places, tz, summaries, StatsSummarizer(entitlements))
    /** P5.3 standing intentions (INTENTIONS.md): chat adds them, capture and typed notes fire them. */
    val intents = IntentService(memory, places, pipeline)
    val intentCommand = IntentCommand(intents)
    val appointmentCommand = AppointmentCommand()
    val intentList = IntentListPresenter(intents, tz, System::currentTimeMillis)
    /** A line the user types (timeline edit, quick note) is a new note: it can set an intention off. */
    private val notesWatched = object : MemoryRepository by memory {
      override fun appendDaily(input: DailyEventInput) = memory.appendDaily(input).also {
        if (input.provenance == Provenance.USER_TYPED) fireIntents { intents.onNote(input.text, System.currentTimeMillis()) }
      }
    }
    val timeline = TimelinePresenter(diary, notesWatched, summaries, tz) { CaptureController.isEnabled(app) }
    /** P3.5 widget / lock-screen / notification-reply notes (write-only). */
    val quickNote = QuickNote(notesWatched, tz) // notesWatched: P5.3 a quick note can fire an intention
    val transparency = TransparencyPresenter(
      memory, ForgetEverything(events, places, ledger, summaries, memory, profile, connections, audit, chatHistory), connections, tz, System::currentTimeMillis,
    )
    val onboarding = OnboardingPresenter(profile, places)
    val modelManager = ModelManagerPresenter(profile)
    /** P3.3: retrieval runs here (DB thread); the model part of [ChatPresenter.answer] runs outside [io]. */
    val chat = ChatPresenter(memory, index, pipeline, tz, thaiSynonyms, nowState = NowStateBuilder(events, places, tz)::build)

    /** Posts what [check] fired. A failure here never breaks capture or the note write that triggered it. */
    fun fireIntents(check: () -> List<IntentFireOutput>) {
      try {
        check().forEach { IntentNotifier.post(app, it) }
      } catch (e: Exception) {
        Log.e("HakuIntent", "intent check failed: ${e::class.simpleName}")
      }
    }

    /**
     * Writes the rule-based diary for a local day before a screen reads it. Idempotent (ledger), so calling it on
     * every open is safe. Also run while charging by NightlyWorker, so the diary exists even when nobody opens the app.
     */
    fun compose(day: String) {
      diary.compose(DiaryComposeInput(day, tz, profile.load().style))
    }

    private var recentComposedAtMs = 0L

    /**
     * Chat: yesterday and today composed before retrieval reads them (as Home does). Each compose renders a whole day, so
     * at most once per [RECENT_COMPOSE_MS]. ponytail: a stay that ended inside that window shows up a few minutes late.
     */
    fun composeRecent(nowUtcMs: Long) {
      if (nowUtcMs - recentComposedAtMs in 0 until RECENT_COMPOSE_MS) return
      // a composer failure must not cost the answer: retrieval then reads what is already written
      runCatching {
        compose(today(nowUtcMs - DAY_MS))
        compose(today(nowUtcMs))
        recentComposedAtMs = nowUtcMs
      }.onFailure { Log.e("HakuShell", "chat-time compose failed: ${it::class.simpleName}") }
    }

    /**
     * haku.db recreated (cleared, restored) while `filesDir/memory` survived: the FTS index would stay empty forever.
     * Null when nothing to do. ponytail: only the empty-index case; a partial desync needs a file-vs-index diff.
     */
    fun rebuildIndexIfEmpty(): RebuildReport? =
      if (index.count() == 0 && (memory.listDays().isNotEmpty() || memory.listCategories().isNotEmpty())) MemoryIndexer(memory, index).rebuild() else null

    /**
     * A fresh engine per run on purpose: it caches Dream's ledger at construction, and forget-everything must not be
     * undone by a stale copy written back. ponytail: template wording only; pass a FactPhraser over the model later.
     */
    fun dreamEngine() = DreamEngine(memory, TextPipeline(IcuWordSegmenter()))

    fun vectorIndexer() = VectorIndexer(memory, vectors, EMBED_MODEL_ID)

    /** B7: recognize the last two weeks of stays, keep the places, and return one unconfirmed home/work guess. */
    fun firstPlaceGuess(nowUtcMs: Long): PlaceGuessOutput? {
      val stays = events.query(CaptureQueryInput(nowUtcMs - GUESS_WINDOW_MS, nowUtcMs))
      val recognized = PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(stays), places.list(), tz))
      places.addNew(recognized.places)
      return recognized.guesses.firstOrNull()
    }
  }

  private val data by lazy { Data() }

  /** P4.1 the one road for actions (chat card, chat notifications, model tools). Audit lines go to the DB thread. */
  val actionGate by lazy {
    DefaultActionGate(
      handlers = listOf(
        CalendarActionHandler(app),
        McpActionHandler({ host -> io { mcpServers.all().firstOrNull { it.host.equals(host, ignoreCase = true) }?.url } }, mcpClient),
      ),
      onAudit = { record -> scope.launch { io { audit.append(record) } } },
      clock = System::currentTimeMillis,
      newId = { UUID.randomUUID().toString() },
    )
  }

  /**
   * Every confirm goes through here (chat card, heads-up offer, model tool card): a `calendar.add` that ran also gets its
   * diary line "นัด …", so the appointment reaches memory whichever road it came by.
   */
  suspend fun confirmAction(id: String): ActionProposalRecord? = actionGate.confirm(id)?.also { record ->
    if (record.status != ActionStatus.DONE) return@also
    val appointment = CalendarAddArgs.toProposal(record) ?: return@also // not a calendar.add
    val note = "นัด ${appointment.title} ${appointment.dayLabel} ${appointment.timeLabel}"
    if (safeIo { quickNote.save(QuickNoteInput(note, System.currentTimeMillis())) } != null) HakuWidget.refresh(app)
  }

  /** Runs [block] on the DB thread, one at a time with capture. */
  suspend fun <T> io(block: Data.() -> T): T = withContext(CaptureController.dispatcher) { data.block() }

  /** Blocking variant for code already on the capture thread (debug receiver). */
  fun onCaptureThread(): Data = data

  val tzOffsetMinutes: Int get() = tz

  fun today(nowUtcMs: Long): String = MemoryFormat.dayOf(nowUtcMs + tz * 60_000L)

  /** Whole GB as sold (a "12 GB" phone reports a bit under 12e9 bytes). */
  fun ramGb(): Int {
    val info = ActivityManager.MemoryInfo()
    app.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return ceil(info.totalMem / 1e9).toInt()
  }

  /** Catalog and imported `.litertlm` files (plus any catalog `.part` partial files) currently on disk. */
  fun installedModels(): List<InstalledModelRecord> = runCatching {
    val catalog = ModelChoice.entries.mapNotNull { c ->
      val f = downloader.file(c)
      val bytes = if (f.exists()) f.length() else 0L
      val part = File(f.path + ".part")
      val partBytes = if (part.exists()) part.length() else 0L
      if (bytes > 0L || partBytes > 0L) InstalledModelRecord(c.fileName, bytes, imported = false, partBytes = partBytes) else null
    }
    catalog + importer.listInstalled()
  }.getOrDefault(emptyList())

  /** P3.8 step 0: the one source of [modelConfig]'s engine and sampler values and of [warmth]'s idle time. */
  val llmSettings = LlmSettingsPrefs(app)

  /** The LLM settings screen's figures: the last warm-up's load and the last chat answer's speed, this process only. */
  val llmMetrics = MutableStateFlow(LlmMetrics())

  /** A step-down or reload for the LLM settings screen to show; null when there is none. */
  val llmNotice = MutableStateFlow<String?>(null)

  /** This process's PSS in MB (the model's native and GPU-mapped memory included as far as Android counts it). */
  fun pssMb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024

  /** Where a LiteRtLocalModel loads from (debug spikes, NightlyWorker, Chat), once the active model is on disk; null before that. */
  suspend fun modelConfig(): ModelConfig? {
    val file = when (val active = io { profile.load().model }) {
      is ActiveModel.Catalog -> downloader.file(active.choice)
      is ActiveModel.Imported -> importer.file(active.fileName)
      ActiveModel.None -> null
    }?.takeIf { it.exists() && it.length() > 0L } ?: return null
    // P3.8: context, backend and sampler from the LLM settings screen
    return llmSettings.load().toModelConfig(file.path, app.cacheDir.path)
  }

  /** EmbeddingGemma for the semantic tier: the model manager's download; debug builds also take the adb-pushed spike copy. */
  fun embeddingModelPath(): String? {
    val f = runCatching { downloader.file(ModelChoice.EMBEDDING_GEMMA_2) }.getOrNull() // external storage can be missing
    if (f != null && f.length() > 0L) return f.path
    return DEBUG_EMBED_PATH.takeIf { BuildConfig.DEBUG && File(it).length() > 0L }
  }

  private val embedLock = Mutex()
  private val indexLock = Mutex()
  private var embedder: LiteRtTextEmbedder? = null

  /** One embedder for chat and indexing (engine opens on first use); null without a model file. */
  private suspend fun embedder(): LiteRtTextEmbedder? = embedLock.withLock {
    val path = embeddingModelPath() ?: return@withLock null
    // own cache dir, as Gallery OnDeviceEmbedder.getIsolatedContext: sharing the LLM's would collide on the compiled GPU program/weight cache
    val cache = File(app.cacheDir, "embed").apply { mkdirs() }.path
    embedder?.takeIf { it.modelPath == path } ?: LiteRtTextEmbedder(path, cacheDir = cache).also {
      embedder?.close()
      embedder = it
    }
  }

  /**
   * Embeds daily lines that have no vector yet ([cap] per run, newest first), then releases the engine. No pending line =
   * no model load. Null when there is no embedding model. Called by NightlyWorker and after bulk writes.
   */
  suspend fun indexVectors(cap: Int = VectorIndexer.DEFAULT_CAP): VectorIndexOutput? = indexLock.withLock {
    val e = embedder() ?: return@withLock null
    try {
      io { vectorIndexer() }.run(e, cap, CaptureController.dispatcher)
    } finally {
      // ponytail: unlocked read; a warm-up landing exactly now can see its embedder closed (that chat goes words-only
      // until the next warm-up), never leaked. Closing is safe under an in-flight embed: both take the embedder's lock.
      if (!warmth.isWarm) e.close()
    }
  }

  /** Background [indexVectors] after a bulk write (demo seed); failures only logged. */
  fun indexVectorsSoon() {
    scope.launch {
      try {
        Log.i(EMBED_TAG, "indexed ${indexVectors()}")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(EMBED_TAG, "vector indexing failed: ${e::class.simpleName}")
      }
    }
  }

  /**
   * Chat-time semantic tier: embeds only the question (~0.3 s) when the engine is open. A cold engine takes ~27 s, so
   * unless [waitForLoad] this question goes words-only ([warmth] is loading it). Null = no model, no
   * vectors yet, or a failure (never throws). The engine opens and closes with [warmth].
   */
  suspend fun questionSemantic(question: String, waitForLoad: Boolean = false, force: Boolean = false): SemanticTier? {
    // eval 2026-10-29 (Fold5, 35 items): tier on 94.3% vs off 91.4%. The earlier "stall" was the cached-app freezer.
    if (!SEMANTIC_CHAT && !force) return null
    try {
      val e = embedder() ?: return null
      val store = io { vectors.takeIf { it.count(EMBED_MODEL_ID) > 0 } } ?: return null
      if (!e.isLoaded && !waitForLoad) return null
      return VectorSemanticTier.forQuestion(question, e, store, EMBED_MODEL_ID, CaptureController.dispatcher)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(EMBED_TAG, "semantic tier unavailable: ${e::class.simpleName}")
      return null
    }
  }

  /** Rebuilt when the embedder is (new model file): its description vectors belong to that engine. */
  private var skillPicker: Pair<LiteRtTextEmbedder, com.bbpillow.haku.skill.EmbeddingSkillPicker>? = null

  /**
   * Week 2: the skill for a message nobody mentioned, or null = plain chat. Same embedder as the semantic tier; while it
   * is cold this returns null ([warmth] is loading it) so a first question never waits on the engine.
   */
  suspend fun pickSkill(text: String, skills: List<com.bbpillow.haku.skill.SkillRecord>): com.bbpillow.haku.skill.SkillRecord? = try {
    val e = embedder()
    when {
      e == null -> null
      !e.isLoaded -> null
      else -> (skillPicker?.takeIf { it.first === e } ?: (e to com.bbpillow.haku.skill.EmbeddingSkillPicker(e)).also { skillPicker = it })
        .second.let { p ->
          if (BuildConfig.DEBUG) Log.d("HakuSkill", "rank=${p.rank(text, skills).joinToString { "${it.first.name}:%.3f".format(it.second) }} chat=%.3f".format(p.anchorScore(text)))
          p.pick(text, skills)
        }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.e(EMBED_TAG, "skill picker unavailable: ${e::class.simpleName}")
    null
  }

  /** The engine reference [warmth] holds; touched only from its event loop. */
  private var warmEngine: Engine? = null

  /**
   * 5ข: the chat tab's LLM engine and embedder, open while the chat is in use. Holds one [SharedEngines] reference, so
   * askHaku's LiteRtLocalModel.load and a skill's LiteRtAgentSession.start (same [modelConfig] key) reuse this engine,
   * and dropping it never closes one a question still holds. Embedder first: LiteRT hangs the next generation when an
   * EmbeddingEngine is created while an LLM engine is open (Fold5, 2026-11-03), so it must never open after the LLM.
   */
  val warmth = ModelWarmth(
    scope,
    { llmSettings.load().idleReleaseSeconds * 1000L },
    warmUp = {
      try {
        embedder()?.load()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(EMBED_TAG, "embedder load failed: ${e::class.simpleName}")
      }
      try {
        if (warmLlm()) llmNotice.compareAndSet(RELOADING_NOTICE, null) // a step-down message stays
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e("HakuModel", "warm-up failed: ${e::class.simpleName}")
      }
    },
    coolDown = {
      warmEngine?.let { warmEngine = null; SharedEngines.release(it) }
      embedLock.withLock { embedder }?.close()
    },
  )

  /**
   * [warmth]'s LLM half, after the embedder: [SharedEngines.acquire] timed into [llmMetrics]. A thrown load failure steps
   * the context down one size, saves it, says so in [llmNotice] and retries; false when even the smallest size failed.
   * True with no model file (nothing failed). A [modelConfig] failure propagates to warmUp's own catch.
   * ponytail: a native abort inside LiteRT kills the process before any catch here, so it cannot step down; the next
   * warm-up tries the same size again. Upgrade path: a "loading" flag saved before acquire, read at start.
   */
  private suspend fun warmLlm(): Boolean {
    while (true) {
      val config = modelConfig() ?: return true
      try {
        val start = SystemClock.elapsedRealtime()
        warmEngine = SharedEngines.acquire(config)
        llmMetrics.update { it.copy(loadMillis = SystemClock.elapsedRealtime() - start, ramMb = pssMb()) }
        return true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e("HakuModel", "warm-up failed at ${config.maxTokens} tokens: ${e::class.simpleName}")
        val smaller = LlmDefaults.stepDown(config.maxTokens)
        if (smaller == null) {
          llmNotice.value = LOAD_FAILED_NOTICE
          return false
        }
        llmSettings.save(llmSettings.load().copy(contextTokens = smaller))
        llmNotice.value = "โหลดบริบท ${LlmSettingsPresenter.label(config.maxTokens)} ไม่สำเร็จ ลดเหลือ ${LlmSettingsPresenter.label(smaller)} ให้แล้ว"
      }
    }
  }

  /**
   * The LLM settings screen's one write. Sampler and idle changes apply from the next conversation / timer; an engine
   * change drops the warm engine and, unless the policy is [ModelLoadPolicy.ON_ASK], loads the new one now. [warmth]
   * runs events in order, so the stop lands before the open.
   * ponytail: an open that arrives while a question still runs cancels that question's pending stop (ModelWarmth.Open
   * clears stopWhenDone), so the old engine stays until the next cool-down and [RELOADING_NOTICE] shows until the next warm-up.
   */
  fun applyLlmSettings(new: LlmSettings) {
    val old = llmSettings.load()
    llmSettings.save(new)
    if (!old.needsReload(new)) return
    warmth.stop()
    val loadsNow = new.loadPolicy != ModelLoadPolicy.ON_ASK
    llmNotice.value = if (loadsNow) RELOADING_NOTICE else null // the user's own choice replaces an older step-down message
    if (loadsNow) warmth.open()
  }

  private companion object {
    const val SEMANTIC_CHAT = true
    const val RELOADING_NOTICE = "กำลังโหลดโมเดลใหม่"
    const val LOAD_FAILED_NOTICE = "โหลดโมเดลไม่สำเร็จ ลองเปลี่ยนตัวประมวลผลหรือโมเดล"
    const val GUESS_WINDOW_MS = 14 * 86_400_000L
    const val DAY_MS = 86_400_000L
    const val RECENT_COMPOSE_MS = 5 * 60_000L
    const val EMBED_TAG = "HakuEmbed"
    const val DEBUG_EMBED_PATH = "/data/local/tmp/eg2.litertlm"
    /** Vectors are tagged with this; another embedding model means re-embed (VectorRepository.retainModel). */
    val EMBED_MODEL_ID = ModelChoice.EMBEDDING_GEMMA_2.name
  }
}
