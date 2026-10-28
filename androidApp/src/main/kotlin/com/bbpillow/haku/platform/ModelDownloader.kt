package com.bbpillow.haku.platform

import android.util.Log
import com.bbpillow.haku.network.DownloadResponseInput
import com.bbpillow.haku.network.DownloadResume
import com.bbpillow.haku.network.ResumeDecision
import com.bbpillow.haku.surface.onboarding.ModelChoice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Download state for a catalog model (onboarding + model manager). [error] is set when it stopped on its own (network, space). */
data class DownloadState(
  val choice: ModelChoice,
  val fraction: Float,
  val paused: Boolean,
  val done: Boolean = false,
  val error: String? = null,
)

/**
 * Resumable HTTP download of a chosen Gemma file into app-specific storage (P3.6, D7). Writes `<name>.part`, resumes
 * with a Range request, renames when the size matches the server's. Pause = cancel the job; the partial file stays.
 *
 * ponytail: a coroutine on the app scope, no foreground service or WorkManager; one download at a time (starting
 * another while one is running returns false instead of queueing).
 */
class ModelDownloader(private val dir: File, private val scope: CoroutineScope) {
  private val _state = MutableStateFlow<DownloadState?>(null)
  val state: StateFlow<DownloadState?> = _state.asStateFlow()
  private var job: Job? = null

  fun file(choice: ModelChoice) = File(dir, choice.fileName)

  /** Starts or resumes [choice]. Returns false when another model is actively downloading right now. */
  fun start(choice: ModelChoice): Boolean {
    val previous = job
    val current = _state.value
    if (previous?.isActive == true && !previous.isCancelled) {
      return current?.choice == choice
    }
    val initialFraction = if (current?.choice == choice && !current.done) {
      current.fraction
    } else {
      val have = partOf(choice).takeIf { it.exists() }?.length() ?: 0L
      DownloadResume.fraction(have, choice.bytes)
    }
    _state.value = DownloadState(choice, initialFraction, paused = false, error = null)
    job = scope.launch(Dispatchers.IO) {
      try {
        previous?.cancelAndJoin() // a paused job may still be writing its last chunk: never two writers on the .part file
        download(choice)
      } catch (e: CancellationException) {
        _state.value = _state.value?.takeIf { it.choice == choice && !it.done }?.copy(paused = true) ?: _state.value
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "model download stopped", e)
        val base = _state.value?.takeIf { it.choice == choice } ?: DownloadState(choice, 0f, true)
        _state.value = base.copy(paused = true, error = e.message ?: e.javaClass.simpleName)
      }
    }
    return true
  }

  fun pause() {
    job?.cancel()
    _state.value = _state.value?.takeIf { !it.done }?.copy(paused = true) ?: _state.value
  }

  /** Cancels any active/paused download for [choice] and removes both the model file and its `.part`. */
  suspend fun delete(choice: ModelChoice) {
    if (_state.value?.choice == choice) {
      job?.cancelAndJoin()
      _state.value = null
    }
    withContext(Dispatchers.IO) {
      file(choice).delete()
      partOf(choice).delete()
    }
  }

  /** App start after onboarding: continue a download the process lost. No-op when nothing was started. */
  fun resumeIfPartial(choice: ModelChoice) {
    if (!file(choice).exists() && partOf(choice).exists()) start(choice)
  }

  private suspend fun download(choice: ModelChoice) {
    val out = file(choice)
    val part = partOf(choice)
    if (out.exists()) return finished(choice)
    dir.mkdirs()
    // one retry from zero when the partial file turns out to belong to another version of the file
    repeat(2) {
      val have = if (part.exists()) part.length() else 0L
      val needed = (choice.bytes - have).coerceAtLeast(0L)
      if (dir.usableSpace < needed) throw IOException("พื้นที่ไม่พอ ต้องว่างอีก ${needed / 1_000_000_000 + 1} GB")
      val conn = URL(choice.url).openConnection() as HttpURLConnection
      try {
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val decision = DownloadResume.decide(
          DownloadResponseInput(have, conn.responseCode, conn.contentLengthLong, conn.getHeaderField("Content-Range")),
        )
        when (decision) {
          is ResumeDecision.Complete -> return rename(choice, part, out)
          is ResumeDecision.StartOver -> part.delete()
          is ResumeDecision.Failed -> throw IOException("HTTP ${decision.status}")
          is ResumeDecision.Write -> {
            write(choice, conn, part, decision)
            if (!DownloadResume.isComplete(part.length(), decision.totalBytes)) throw IOException("ไฟล์ไม่ครบ")
            return rename(choice, part, out)
          }
        }
      } finally {
        conn.disconnect()
      }
    }
    throw IOException("เซิร์ฟเวอร์ส่งไฟล์ไม่ตรงกัน")
  }

  private suspend fun write(choice: ModelChoice, conn: HttpURLConnection, part: File, decision: ResumeDecision.Write) {
    var have = decision.fromByte
    var lastReported = -1
    conn.inputStream.use { input ->
      FileOutputStream(part, decision.fromByte > 0).use { output ->
        val buf = ByteArray(BUFFER)
        while (true) {
          coroutineContext.ensureActive() // pause
          val n = input.read(buf)
          if (n < 0) break
          output.write(buf, 0, n)
          have += n
          val fraction = DownloadResume.fraction(have, decision.totalBytes)
          val permille = (fraction * 1000).toInt()
          if (permille != lastReported) {
            lastReported = permille
            _state.value = DownloadState(choice, fraction, paused = false)
          }
        }
      }
    }
  }

  private fun rename(choice: ModelChoice, part: File, out: File) {
    if (!part.renameTo(out)) throw IOException("rename failed")
    finished(choice)
  }

  private fun finished(choice: ModelChoice) {
    _state.value = DownloadState(choice, 1f, paused = false, done = true)
  }

  private fun partOf(choice: ModelChoice) = File(dir, choice.fileName + ".part")

  private companion object {
    const val TAG = "HakuModel"
    const val TIMEOUT_MS = 30_000
    const val BUFFER = 256 * 1024
  }
}
