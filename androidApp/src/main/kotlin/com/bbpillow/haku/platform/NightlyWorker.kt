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
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.summary.EveningSummarizer
import com.bbpillow.haku.thinking.summary.EveningSummaryInput
import com.bbpillow.haku.thinking.summary.NightlyPlan
import com.bbpillow.haku.thinking.summary.NightlyPlanInput
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * P2.7 "runs while charging, screen off": composes the rule-based diary for yesterday and today, then the evening
 * summary with the downloaded model for the days [NightlyPlan] picks. Idempotent (diary ledger, summary stored once).
 * The model is loaded only when a day needs it and released at the end, so the shared engine never outlives the job.
 */
class NightlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private class Facts(val style: DiaryStyle, val lines: Map<String, List<String>>, val stored: Set<String>)

  override suspend fun doWork(): Result {
    val graph = (applicationContext as HakuApplication).graph
    val now = System.currentTimeMillis()
    val tz = graph.tzOffsetMinutes
    val days = NightlyPlan.candidateDays(now, tz)
    val facts = graph.io {
      val profile = profile.load()
      if (!NightlyPlan.shouldCompose(profile.onboarded)) return@io null
      days.forEach { compose(it) }
      Facts(profile.style, days.associateWith { d -> diary.readDay(d, tz).map { it.text } }, days.filter { summaries.find(it) != null }.toSet())
    } ?: return Result.success().also { log("not onboarded, skipped") }

    val config = graph.modelConfig()
    val plan = NightlyPlan.plan(NightlyPlanInput(true, config != null, now, tz, facts.lines.mapValues { it.value.size }, facts.stored))
    log("diary composed for $days; model=${config != null} summarize=${plan.summarizeDays}")
    if (config == null || plan.summarizeDays.isEmpty()) return Result.success()

    val model = LiteRtLocalModel()
    try {
      val client = LocalModelClient(model)
      client.load(config)
      val summarizer = EveningSummarizer(client, graph.io { summaries })
      for (day in plan.summarizeDays) {
        // store reads/writes on the DB thread; the model itself runs on Default and only suspends this one
        val out = withContext(CaptureController.dispatcher) {
          summarizer.summarize(EveningSummaryInput(day, facts.lines.getValue(day), facts.style))
        }
        log("summary $day source=${out.source} rejected=${out.rejectedReasons} metrics=${out.metrics}")
      }
    } catch (e: CancellationException) {
      throw e // constraints lost (unplugged, screen on): WorkManager runs it again next window
    } catch (e: Exception) {
      // the diary is already written; the summary falls back to the template on Home until a later run succeeds
      Log.e(TAG, "nightly summary failed: ${e::class.simpleName}")
    } finally {
      model.unload()
    }
    return Result.success()
  }

  private fun log(msg: String) {
    if (BuildConfig.DEBUG) Log.i(TAG, msg) // days and summary sources are personal; never in release logs
  }

  companion object {
    const val TAG = "HakuNightly"
    private const val NAME = "haku-nightly"
    private const val REPEAT_HOURS = 3L

    /** After onboarding (and on every main-screen start; KEEP makes it a no-op once scheduled). Survives reboots via WorkManager. */
    fun schedule(context: Context) {
      val constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresBatteryNotLow(true)
        .build()
      // ponytail: every 3 h so a night of charging gets a run after 21:00; NightlyPlan keeps repeats cheap (no model load)
      val request = PeriodicWorkRequestBuilder<NightlyWorker>(REPEAT_HOURS, TimeUnit.HOURS).setConstraints(constraints).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Test hook (debug receiver): one run now, no constraints. */
    fun runNow(context: Context) {
      WorkManager.getInstance(context).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<NightlyWorker>().build())
    }
  }
}
