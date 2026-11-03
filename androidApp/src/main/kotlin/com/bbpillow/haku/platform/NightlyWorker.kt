package com.bbpillow.haku.platform

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bbpillow.haku.BuildConfig
import com.bbpillow.haku.AppGraph
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.memory.dream.DreamInput
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.platform.widget.HakuWidget
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.summary.EveningSummarizer
import com.bbpillow.haku.thinking.summary.EveningSummaryInput
import com.bbpillow.haku.thinking.summary.NightlyPlan
import com.bbpillow.haku.thinking.summary.NightlyPlanInput
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * P2.7 "runs while charging": composes the rule-based diary for yesterday and today, then embeds new diary lines for the
 * semantic tier (ชิ้น ค, only if the EmbeddingGemma file exists), then the evening summary with the downloaded model for
 * the days [NightlyPlan] picks, then Dream (no model) for local yesterday. Idempotent (diary ledger, summary stored once,
 * lines with a vector skipped). Each model is loaded only when needed and released after its step. Embedding comes
 * before the LLM: an EmbeddingEngine created while an LLM engine is open hangs the LLM (see LiteRtTextEmbedder).
 */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private class Facts(val style: DiaryStyle, val lines: Map<String, List<String>>, val stored: Set<String>, val dreamed: Set<String>)

  override suspend fun doWork(): Result {
    val graph = (applicationContext as HakuApplication).graph
    val now = System.currentTimeMillis()
    val tz = graph.tzOffsetMinutes
    val days = NightlyPlan.candidateDays(now, tz)
    val facts = graph.io {
      val profile = profile.load()
      if (!NightlyPlan.shouldCompose(profile.onboarded)) return@io null
      // calendar-only capture has no wake-ups (no activity transitions): this run is when its calendar gets re-read
      if (CaptureController.isEnabled(applicationContext)) {
        runCatching { CaptureController.catchUp(applicationContext, now) }.onFailure { Log.e(TAG, "catch-up failed: ${it::class.simpleName}") }
      }
      days.forEach { compose(it) }
      Facts(profile.style, days.associateWith { d -> diary.readDay(d, tz).map { it.text } }, days.filter { summaries.find(it) != null }.toSet(), memory.listDreamDays().toSet())
    } ?: return Result.success().also { log("not onboarded, skipped") }

    // external storage can be unavailable: treat that as "no model", the diary and Dream still run
    val config = try {
      graph.modelConfig()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "model config unavailable: ${e::class.simpleName}")
      null
    }
    val plan = NightlyPlan.plan(NightlyPlanInput(true, config != null, now, tz, facts.lines.mapValues { it.value.size }, facts.stored, facts.dreamed))
    log("diary composed for $days; model=${config != null} summarize=${plan.summarizeDays}")
    embed(graph) // before summarize: the embedder must open before (or after) the LLM, never while it is open
    if (config != null && plan.summarizeDays.isNotEmpty()) summarize(graph, config, plan.summarizeDays, facts)
    dream(graph, plan.dreamDay)
    HakuWidget.update(applicationContext) // P3.5: the new diary/summary shows on the widget
    return Result.success()
  }

  private suspend fun summarize(graph: AppGraph, config: ModelConfig, summarizeDays: List<String>, facts: Facts) {
    val model = LiteRtLocalModel()
    try {
      val client = LocalModelClient(model)
      client.load(config)
      val summarizer = EveningSummarizer(client, graph.io { summaries })
      for (day in summarizeDays) {
        // store reads/writes on the DB thread; the model itself runs on Default and only suspends this one
        val out = withContext(CaptureController.dispatcher) {
          // the model load was slow: re-read now, a delete/forget since the first read must not be summarized
          val data = graph.onCaptureThread()
          val fresh = data.diary.readDay(day, graph.tzOffsetMinutes).map { it.text }
          if (fresh.isEmpty() || fresh != facts.lines[day]) return@withContext null
          summarizer.summarize(EveningSummaryInput(day, fresh, facts.style)).also {
            // lines deleted while the model was generating: drop what was just stored
            if (data.diary.readDay(day, graph.tzOffsetMinutes).map { l -> l.text } != fresh) data.summaries.delete(day)
          }
        }
        if (out == null) log("summary $day skipped: lines changed") else log("summary $day source=${out.source} rejected=${out.rejectedReasons} metrics=${out.metrics}")
      }
    } catch (e: CancellationException) {
      throw e // constraints lost (unplugged): WorkManager runs it again next window
    } catch (e: Exception) {
      // the diary is already written; the summary falls back to the template on Home until a later run succeeds
      Log.e(TAG, "nightly summary failed: ${e::class.simpleName}")
    } finally {
      model.unload()
    }
  }

  /**
   * P1.8 after diary and summary: memory consolidation for [day] (local yesterday, used as the Dream day, see
   * NightlyPlan.plan). Idempotent (engine returns the stored report). Never fails the job: the diary is already safe.
   */
  private suspend fun dream(graph: AppGraph, day: String?) {
    if (day == null) return
    try {
      val out = withContext(CaptureController.dispatcher) { graph.onCaptureThread().dreamEngine().dream(DreamInput(day)) }
      log("dream $day alreadyRan=${out.alreadyRan} tidied=${out.tidied.size} promoted=${out.promoted.size} held=${out.heldBack.size}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "nightly dream failed: ${e::class.simpleName}")
    }
  }

  /** Vectors for lines written since the last run (cap per run); indexVectors releases the engine unless the chat is warm. */
  private suspend fun embed(graph: AppGraph) {
    try {
      log("vectors ${graph.indexVectors() ?: "skipped: no embedding model"}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "nightly embedding failed: ${e::class.simpleName}")
    }
  }

  private fun log(msg: String) {
    if (BuildConfig.DEBUG) Log.i(TAG, msg) // days and summary sources are personal; never in release logs
  }

  companion object {
    const val TAG = "HakuNightly"
    private const val NAME = "haku-nightly"
    private const val REPEAT_HOURS = 3L

    /**
     * After onboarding and on every main-screen start. UPDATE keeps the period's timing but swaps in these constraints,
     * so a phone that got the old device-idle request is fixed on its next app start. Survives reboots via WorkManager.
     * No device-idle: a phone kept awake on its charger never goes idle, so the job never ran. Charging stays required:
     * the run loads the LLM and EmbeddingGemma, fine on the charger even with the screen on (the capture thread is shared,
     * so a screen read can wait behind one compose; the model runs off it).
     */
    fun schedule(context: Context) {
      val constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()
      // ponytail: every 3 h so a night of charging gets a run after 21:00; NightlyPlan keeps repeats cheap (no model load)
      val request = PeriodicWorkRequestBuilder<NightlyWorker>(REPEAT_HOURS, TimeUnit.HOURS).setConstraints(constraints).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Test hook (debug receiver): one run now, no constraints. */
    fun runNow(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NightlyWorker>().build())
    }
  }
}
