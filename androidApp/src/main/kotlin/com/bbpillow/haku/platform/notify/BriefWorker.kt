package com.bbpillow.haku.platform.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bbpillow.haku.BuildConfig
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.settings.BriefKind
import com.bbpillow.haku.surface.settings.DailyBrief
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * P5.1/P5.2 one run per brief per day: a one-time work at the local time that posts the brief, then enqueues itself for
 * tomorrow. WorkManager persists it across app kills and reboots.
 * ponytail: WorkManager may run some minutes late in Doze; switch to an exact alarm if users notice.
 */
class BriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val kind = BriefKind.entries.firstOrNull { it.name == inputData.getString(KEY_KIND) } ?: return Result.failure()
    val graph = (applicationContext as HakuApplication).graph
    val profile = try {
      graph.io { profile.load() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "profile unavailable: ${e::class.simpleName}")
      return Result.retry()
    }
    if (!profile.onboarded || !DailyBrief.isOn(profile, kind)) return Result.success() // turned off: chain ends
    val minute = DailyBrief.minuteOf(profile, kind)
    try {
      if (DailyBrief.isOnTime(System.currentTimeMillis(), graph.tzOffsetMinutes, minute)) postOncePerDay(applicationContext, kind, force = false)
      else log("$kind late, skipped")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "brief failed: ${e::class.simpleName}") // today's brief is lost; tomorrow's is still enqueued below
    }
    enqueue(applicationContext, kind, minute, ExistingWorkPolicy.APPEND_OR_REPLACE) // runs after this one finishes
    return Result.success()
  }

  companion object {
    private const val TAG = "HakuBrief"
    private const val KEY_KIND = "kind"
    private const val PREFS = "haku-brief"

    private fun name(kind: BriefKind) = "haku-brief-${kind.name.lowercase()}"

    /** Settings changed (REPLACE) or the main screen started (KEEP): one pending run per brief that is on, none otherwise. */
    fun sync(context: Context, profile: ProfileRecord, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
      BriefKind.entries.forEach { kind ->
        if (profile.onboarded && DailyBrief.isOn(profile, kind)) enqueue(context, kind, DailyBrief.minuteOf(profile, kind), policy)
        else WorkManager.getInstance(context).cancelUniqueWork(name(kind))
      }
    }

    private fun enqueue(context: Context, kind: BriefKind, minute: Int, policy: ExistingWorkPolicy) {
      val graph = (context.applicationContext as HakuApplication).graph
      val now = System.currentTimeMillis()
      val delay = DailyBrief.nextFireUtc(now, graph.tzOffsetMinutes, minute) - now
      val request = OneTimeWorkRequestBuilder<BriefWorker>()
        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(KEY_KIND to kind.name))
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(name(kind), policy, request)
    }

    /**
     * Builds and posts the brief. At most one per kind per local day (P5.5) unless [force] (debug trigger); nothing to say
     * = no notification. Returns what happened, for the debug log.
     */
    suspend fun postOncePerDay(context: Context, kind: BriefKind, force: Boolean): String {
      val graph = (context.applicationContext as HakuApplication).graph
      val now = System.currentTimeMillis()
      val today = graph.today(now)
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      if (!force && prefs.getString(kind.name, null) == today) return "already posted today".also(::log)
      val notice = graph.io {
        val style = profile.load().style
        compose(graph.today(now - 86_400_000L)) // the diary exists even if nobody opened the app (as Home does)
        compose(today)
        DailyBrief.compose(kind, home.load(now, style), style)
      } ?: return "nothing to say".also(::log)
      if (!BriefNotifier.post(context, kind, notice)) return "notifications off".also(::log)
      prefs.edit().putString(kind.name, today).apply()
      return "posted".also { log("$kind $it") }
    }

    private fun log(msg: String) {
      if (BuildConfig.DEBUG) Log.i(TAG, msg) // never the text: it is the user's day
    }
  }
}
