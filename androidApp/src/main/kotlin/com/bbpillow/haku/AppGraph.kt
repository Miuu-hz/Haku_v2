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
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.platform.IcuWordSegmenter
import com.bbpillow.haku.platform.ModelDownloader
import com.bbpillow.haku.platform.billing.PlayBilling
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.home.HomeBriefPresenter
import com.bbpillow.haku.surface.memory.ForgetEverything
import com.bbpillow.haku.surface.memory.TransparencyPresenter
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.plus.EntitlementStore
import com.bbpillow.haku.surface.timeline.TimelinePresenter
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

  val downloader by lazy { ModelDownloader(requireNotNull(app.getExternalFilesDir("models")) { "no app-specific storage" }, scope) }

  /** The stores and presenters. Touch only inside [io]: their constructors create tables. */
  inner class Data {
    private val db = CaptureController.connection(app)
    val profile = ProfileStore(FileSystem.SYSTEM, app.filesDir.resolve("profile.txt").path.toPath())
    val events = CaptureEventStore(db)
    val places = PlaceStore(db)
    val memory = IndexedMemoryRepository(
      MemoryStore(FileSystem.SYSTEM, app.filesDir.resolve("memory").path.toPath()),
      SearchIndexStore(db, TextPipeline(IcuWordSegmenter())),
    )
    val ledger = DiaryLedgerStore(db)
    val diary = DiaryComposer(events, places, memory, ledger)
    /** P2.7 evening summaries, one file per local day (written by NightlyWorker, read by Home). */
    val summaries = EveningSummaryStore(FileSystem.SYSTEM, app.filesDir.resolve("summaries").path.toPath())
    val home = HomeBriefPresenter(diary, events, places, tz, summaries, StatsSummarizer(entitlements))
    val timeline = TimelinePresenter(diary, memory, summaries, tz)
    val transparency = TransparencyPresenter(memory, ForgetEverything(events, places, ledger, summaries, memory, profile), tz)
    val onboarding = OnboardingPresenter(profile, places)

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

  /** Where a LiteRtLocalModel loads from (debug spikes, NightlyWorker), once the chosen model is fully downloaded; null before that. */
  suspend fun modelConfig(): ModelConfig? {
    val file = downloader.file(io { profile.load().model }).takeIf { it.exists() } ?: return null
    // ponytail: GPU as in the P0.4 spike; fall back to CPU when the Fold5 numbers say so
    return ModelConfig(file.path, ModelBackend.Gpu, cacheDir = app.cacheDir.path)
  }

  private companion object {
    const val GUESS_WINDOW_MS = 14 * 86_400_000L
  }
}
