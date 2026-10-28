package com.bbpillow.haku.platform

import android.util.Log
import com.bbpillow.haku.network.ConnectionPurpose
import com.bbpillow.haku.network.ConnectionRecord
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
import kotlinx.coroutines.NonCancellable
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
 *
 * Every HTTP request it opens is handed to [onConnection] (E3 connection log), redirect hops included.
 */
class ModelDownloader(
  private val dir: File,
  private val scope: CoroutineScope,
  private val onConnection: suspend (ConnectionRecord) -> Unit,
) {
  private val _state = MutableStateFlow<DownloadState?>(null)
  val state: StateFlow<DownloadState?> = _state.asStateFlow()
  private var job: Job? = null
  private val pausedChoices = mutableSetOf<ModelChoice>()

  fun file(choice: ModelChoice) = File(dir, choice.fileName)

  /** Starts or resumes [choice]. Returns false when another model is actively downloading right now. */
  fun start(choice: ModelChoice): Boolean {
    val previous = job
    val current = _state.value
    if (previous?.isActive == true && !previous.isCancelled) {
      return current?.choice == choice
    }
    pausedChoices.remove(choice)
    val initialFraction = if (current?.choice == choice && !current.done) current.fraction else 0f
    _state.value = DownloadState(choice, initialFraction, paused = false, error = null)
    job = scope.launch(Dispatchers.IO) {
      try {
        previous?.cancelAndJoin() // a paused job may still be writing its last chunk: never two writers on the .part file
        download(choice)
      } catch (e: CancellationException) {
        if (job === coroutineContext[Job]) {
          _state.value = _state.value?.takeIf { it.choice == choice && !it.done }?.copy(paused = true) ?: _state.value
        }
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "model download stopped", e)
        val have = partOf(choice).takeIf { it.exists() }?.length() ?: 0L
        if (have <= 0L) partOf(choice).delete()
        val fraction = if (have > 0L) DownloadResume.fraction(have, choice.bytes) else 0f
        _state.value = DownloadState(
          choice = choice,
          fraction = fraction,
          paused = have > 0L,
          error = e.message ?: e.javaClass.simpleName,
        )
      }
    }
    return true
  }

  fun pause() {
    _state.value?.takeIf { !it.done }?.let {
      pausedChoices.add(it.choice)
      _state.value = it.copy(paused = true)
    }
    job?.cancel()
  }

  fun clearError() {
    val cur = _state.value ?: return
    if (cur.error != null) {
      _state.value = if (cur.paused && cur.fraction > 0f) cur.copy(error = null) else null
    }
  }

  /** Cancels any active/paused download for [choice] and removes both the model file and its `.part`. */
  suspend fun delete(choice: ModelChoice) {
    pausedChoices.remove(choice)
    if (_state.value?.choice == choice) {
      val active = job
      job = null
      active?.cancelAndJoin()
    }
    withContext(Dispatchers.IO) {
      file(choice).delete()
      partOf(choice).delete()
    }
    if (_state.value?.choice == choice) {
      _state.value = null
    }
  }

  /** App start after onboarding: continue a download the process lost. No-op when nothing was started. */
  fun resumeIfPartial(choice: ModelChoice) {
    if (_state.value?.paused == true || choice in pausedChoices) return
    val previous = job
    if (previous?.isActive == true && !previous.isCancelled) return
    scope.launch(Dispatchers.IO) {
      if (_state.value?.paused == true || choice in pausedChoices) return@launch
      if (!file(choice).exists() && partOf(choice).exists()) start(choice)
    }
  }

  private suspend fun download(choice: ModelChoice) {
    val out = file(choice)
    val part = partOf(choice)
    if (out.exists()) return finished(choice)
    dir.mkdirs()
    // one retry from zero when the partial file turns out to belong to another version of the file
    repeat(2) {
      val have = if (part.exists()) part.length() else 0L
      _state.value = DownloadState(choice, DownloadResume.fraction(have, choice.bytes), paused = false)
      val needed = (choice.bytes - have).coerceAtLeast(0L)
      if (dir.usableSpace < needed) throw IOException("พื้นที่ไม่พอ ต้องว่างอีก ${needed / 1_000_000_000 + 1} GB")
      val conn = URL(choice.url).openConnection() as HttpURLConnection
      val startedAt = System.currentTimeMillis()
      var received = 0L
      var result = FAILED
      try {
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val decision = DownloadResume.decide(
          DownloadResponseInput(have, conn.responseCode, conn.contentLengthLong, conn.getHeaderField("Content-Range")),
        )
        val finalUrl = conn.url.toString()
        if (finalUrl != choice.url) log(startedAt, choice.url, null, "ส่งต่อไปที่ ${ConnectionRecord.hostAndPath(finalUrl).first}")
        when (decision) {
          is ResumeDecision.Complete -> {
            rename(choice, part, out)
            result = OK
            return
          }
          is ResumeDecision.StartOver -> {
            part.delete()
            result = "ไฟล์ในเครื่องไม่ตรง เริ่มใหม่"
          }
          is ResumeDecision.Failed -> {
            result = "$FAILED (HTTP ${decision.status})"
            throw IOException("HTTP ${decision.status}")
          }
          is ResumeDecision.Write -> {
            write(choice, conn, part, decision) { received += it }
            if (!DownloadResume.isComplete(part.length(), decision.totalBytes)) throw IOException("ไฟล์ไม่ครบ")
            rename(choice, part, out)
            result = OK
            return
          }
        }
      } catch (e: CancellationException) {
        result = "หยุดไว้"
        throw e
      } catch (e: Exception) {
        if (result == FAILED) result = "$FAILED (${e.javaClass.simpleName})" // class name only: messages can hold a URL
        throw e
      } finally {
        conn.disconnect()
        log(startedAt, conn.url.toString(), received, result)
      }
    }
    throw IOException("เซิร์ฟเวอร์ส่งไฟล์ไม่ตรงกัน")
  }

  /** Records one connection; runs even while the job is being cancelled (pause), and never fails the download. */
  private suspend fun log(atMs: Long, url: String, bytesIn: Long?, result: String) = withContext(NonCancellable) {
    try {
      onConnection(ConnectionRecord.of(atMs, url, ConnectionPurpose.MODEL_DOWNLOAD, bytesIn, null, result))
    } catch (e: Exception) {
      Log.w(TAG, "connection log write failed: ${e.javaClass.simpleName}")
    }
  }

  private suspend fun write(choice: ModelChoice, conn: HttpURLConnection, part: File, decision: ResumeDecision.Write, onBytes: (Int) -> Unit) {
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
          onBytes(n)
          coroutineContext.ensureActive()
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
    const val OK = "สำเร็จ"
    const val FAILED = "ไม่สำเร็จ"
    const val TIMEOUT_MS = 30_000
    const val BUFFER = 256 * 1024
  }
}
