package com.bbpillow.haku

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
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.VectorStore
import com.bbpillow.haku.network.ConnectionStore
import com.bbpillow.haku.platform.IcuWordSegmenter
import com.bbpillow.haku.platform.LiteRtTextEmbedder
import com.bbpillow.haku.platform.ModelDownloader
import com.bbpillow.haku.platform.ModelImporter
import com.bbpillow.haku.platform.billing.PlayBilling
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.chat.ChatPresenter
import com.bbpillow.haku.surface.chat.NowStateBuilder
import com.bbpillow.haku.surface.home.HomeBriefPresenter
import com.bbpillow.haku.surface.memory.ForgetEverything
import com.bbpillow.haku.surface.memory.TransparencyPresenter
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.plus.EntitlementStore
import com.bbpillow.haku.surface.settings.InstalledModelRecord
import com.bbpillow.haku.surface.settings.ModelManagerPresenter
import com.bbpillow.haku.surface.timeline.TimelinePresenter
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.context.SemanticTier
import com.bbpillow.haku.thinking.context.VectorIndexOutput
import com.bbpillow.haku.thinking.context.VectorIndexer
import com.bbpillow.haku.thinking.context.VectorSemanticTier
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.io.File
import java.util.TimeZone
import kotlin.math.ceil
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Manual DI (P3.6): one SQLite connection (CaptureController's, `noBackupFilesDir/haku.db`), the stores over it and the
 * presenters, each built once. Stores and memory files are not thread-safe, so every call goes through [io], which runs
 * on the capture thread. Built in [HakuApplication.onCreate]; nothing opens until the first [io] call.
 * ponytail: tz offset fixed per process (Thailand has no DST); pass it per call if travel across zones matters.
 */
class AppGraph(private val app: Application) {
  private val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

  /** Outlives activities (fold/unfold, backgrounding) so the model download keeps going. */
  val scope = CoroutineScope(SupervisorJob())

  /** P9.4 Haku Plus. Built on first use (not in worker processes that never ask); the file goes through the capture thread. */
  val billing by lazy {
    PlayBilling(app, EntitlementStore(FileSystem.SYSTEM, app.filesDir.resolve("entitlement.txt").path.toPath()), CaptureController.dispatcher, scope)
  }

  /** What the stats gate asks; follows [PlayBilling.entitlements], so a purchase or an expired grace shows on the next load. */
  val entitlements = object : Entitlements { override val plan: Plan get() = billing.entitlements.value.plan }

  private val modelsDir by lazy { requireNotNull(app.getExternalFilesDir("models")) { "no app-specific storage" } }
  /** Every connection it opens goes into the E3 connection log (Transparency screen). */
  val downloader by lazy { ModelDownloader(modelsDir, scope) { record -> io { connections.add(record) } } }
  val importer by lazy { ModelImporter(app, modelsDir, scope) }

  /** The stores and presenters. Touch only inside [io]: their constructors create tables. */
  inner class Data {
    private val db = CaptureController.connection(app)
    val profile = ProfileStore(FileSystem.SYSTEM, app.filesDir.resolve("profile.txt").path.toPath())
    val events = CaptureEventStore(db)
    val places = PlaceStore(db)
    private val pipeline = TextPipeline(IcuWordSegmenter())
    private val index = SearchIndexStore(db, pipeline)
    /** ชิ้น ค embeddings: same no-backup DB as the FTS index, deleted with each line and by forget-everything. */
    val vectors = VectorStore(db)
    val memory = IndexedMemoryRepository(MemoryStore(FileSystem.SYSTEM, app.filesDir.resolve("memory").path.toPath()), index, vectors)
    val ledger = DiaryLedgerStore(db)
    /** E3: connections the app opened, 30 days. ponytail: Play Billing is IPC to the Play Store app, which connects for us, so not logged. */
    val connections = ConnectionStore(db)
    val diary = DiaryComposer(events, places, memory, ledger)
    /** P2.7 evening summaries, one file per local day (written by NightlyWorker, read by Home). */
    val summaries = EveningSummaryStore(FileSystem.SYSTEM, app.filesDir.resolve("summaries").path.toPath())
    val home = HomeBriefPresenter(diary, events, places, tz, summaries, StatsSummarizer(entitlements))
    val timeline = TimelinePresenter(diary, memory, summaries, tz)
    val transparency = TransparencyPresenter(
      memory, ForgetEverything(events, places, ledger, summaries, memory, profile, connections), connections, tz, System::currentTimeMillis,
    )
    val onboarding = OnboardingPresenter(profile, places)
    val modelManager = ModelManagerPresenter(profile)
    /** P3.3: retrieval runs here (DB thread); the model part of [ChatPresenter.answer] runs outside [io]. */
    val chat = ChatPresenter(memory, index, pipeline, tz, thaiSynonyms, nowState = NowStateBuilder(events, places, tz)::build)

    /**
     * Writes the rule-based diary for a local day before a screen reads it. Idempotent (ledger), so calling it on
     * every open is safe. Also run while charging by NightlyWorker, so the diary exists even when nobody opens the app.
     */
    fun compose(day: String) {
      diary.compose(DiaryComposeInput(day, tz, profile.load().style))
    }

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

  /** Where a LiteRtLocalModel loads from (debug spikes, NightlyWorker, Chat), once the active model is on disk; null before that. */
  suspend fun modelConfig(): ModelConfig? {
    val file = when (val active = io { profile.load().model }) {
      is ActiveModel.Catalog -> downloader.file(active.choice)
      is ActiveModel.Imported -> importer.file(active.fileName)
      ActiveModel.None -> null
    }?.takeIf { it.exists() && it.length() > 0L } ?: return null
    // ponytail: GPU as in the P0.4 spike; fall back to CPU when the Fold5 numbers say so
    return ModelConfig(file.path, ModelBackend.Gpu, cacheDir = app.cacheDir.path)
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
    embedder?.takeIf { it.modelPath == path } ?: LiteRtTextEmbedder(path, cacheDir = app.cacheDir.path).also {
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
      e.close()
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
   * unless [waitForLoad] it starts loading in the background and this question goes words-only. Null = no model, no
   * vectors yet, or a failure (never throws). ponytail: the engine stays open for the process once warm; release on
   * onTrimMemory if RAM pressure shows up.
   */
  suspend fun questionSemantic(question: String, waitForLoad: Boolean = false, force: Boolean = false): SemanticTier? {
    // eval 2026-10-29 (Fold5, 35 items): tier on 94.3% vs off 91.4%. The earlier "stall" was the cached-app freezer.
    if (!SEMANTIC_CHAT && !force) return null
    try {
      val e = embedder() ?: return null
      val store = io { vectors.takeIf { it.count(EMBED_MODEL_ID) > 0 } } ?: return null
      if (!e.isLoaded && !waitForLoad) return null.also { warm(e) }
      return VectorSemanticTier.forQuestion(question, e, store, EMBED_MODEL_ID, CaptureController.dispatcher)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(EMBED_TAG, "semantic tier unavailable: ${e::class.simpleName}")
      return null
    }
  }

  /** Chat tab opened: open the engine early so the first question can use the semantic tier. */
  fun warmEmbedder() {
    scope.launch {
      try {
        val e = embedder() ?: return@launch
        if (!e.isLoaded && io { vectors.count(EMBED_MODEL_ID) } > 0) warm(e)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(EMBED_TAG, "embedder warm-up failed: ${e::class.simpleName}")
      }
    }
  }

  private fun warm(e: LiteRtTextEmbedder) {
    scope.launch { runCatching { e.load() }.onFailure { Log.e(EMBED_TAG, "embedder load failed: ${it::class.simpleName}") } }
  }

  private companion object {
    const val SEMANTIC_CHAT = true
    const val GUESS_WINDOW_MS = 14 * 86_400_000L
    const val EMBED_TAG = "HakuEmbed"
    const val DEBUG_EMBED_PATH = "/data/local/tmp/eg2.litertlm"
    /** Vectors are tagged with this; another embedding model means re-embed (VectorRepository.retainModel). */
    val EMBED_MODEL_ID = ModelChoice.EMBEDDING_GEMMA_2.name
  }
}
