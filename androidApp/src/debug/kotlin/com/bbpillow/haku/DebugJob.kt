package com.bbpillow.haku

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Debug receivers' long work (model loads, evals, MCP calls) runs inside an expedited WorkManager job. Launching it on the
 * app scope alone let onReceive return at once, the process went cached, and the cached-apps freezer stopped it ~10 s
 * later mid model load: it looked like a GPU hang in kgsl (Fold5 2026-11-03, `dumpsys activity` "Apps frozen" listed
 * Haku for 90 s). A process running a job is never frozen, which is why NightlyWorker never hit this.
 */
object DebugJob {
  private const val KEY = "id"
  private val pending = ConcurrentHashMap<String, suspend () -> Unit>()

  fun start(context: Context, block: suspend () -> Unit) {
    val id = UUID.randomUUID().toString()
    pending[id] = block
    WorkManager.getInstance(context).enqueue(
      OneTimeWorkRequestBuilder<Worker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(workDataOf(KEY to id))
        .build(),
    )
  }

  /** ponytail: the block lives in this process only; a job WorkManager replays after a process death finds nothing and ends. */
  class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
      val block = pending.remove(inputData.getString(KEY)) ?: return Result.success()
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        Log.e("HakuDebugJob", "debug job failed", t)
      }
      return Result.success()
    }
  }
}
