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
import kotlin.coroutines.coroutineContext

/** Download state for the onboarding MODEL step. [error] is set when it stopped on its own (network, space). */
data class DownloadState(val fraction: Float, val paused: Boolean, val done: Boolean = false, val error: String? = null)

/**
 * Resumable HTTP download of the chosen Gemma file into app-specific storage (P3.6, D7). Writes `<name>.part`, resumes
 * with a Range request, renames when the size matches the server's. Pause = cancel the job; the partial file stays.
 *
 * ponytail: a coroutine on the app scope, no foreground service or WorkManager. It survives activity recreation and the
 * user moving through onboarding; if Android kills the process in the background the `.part` file stays and
 * [resumeIfPartial] continues it on the next launch. Add a dataSync foreground service if testers see it stall.
 */
class ModelDownloader(private val dir: File, private val scope: CoroutineScope) {
  private val _state = MutableStateFlow<DownloadState?>(null)
  val state: StateFlow<DownloadState?> = _state.asStateFlow()
  private var job: Job? = null

  fun file(choice: ModelChoice) = File(dir, sourceOf(choice).fileName)

  fun start(choice: ModelChoice) {
    val previous = job
    if (previous?.isActive == true && !previous.isCancelled) return
    _state.value = (_state.value ?: DownloadState(0f, paused = false)).copy(paused = false, error = null)
    job = scope.launch(Dispatchers.IO) {
      try {
        previous?.cancelAndJoin() // a paused job may still be writing its last chunk: never two writers on the .part file
        download(choice)
      } catch (e: CancellationException) {
        _state.value = _state.value?.copy(paused = true) // a chunk may have reported "running" after pause()
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "model download stopped", e)
        _state.value = (_state.value ?: DownloadState(0f, true)).copy(paused = true, error = e.message ?: e.javaClass.simpleName)
      }
    }
  }

  fun pause() {
    job?.cancel()
    _state.value = _state.value?.takeIf { !it.done }?.copy(paused = true) ?: _state.value
  }

  /** App start after onboarding: continue a download the process lost. No-op when nothing was started. */
  fun resumeIfPartial(choice: ModelChoice) {
    if (!file(choice).exists() && partOf(choice).exists()) start(choice)
  }

  private suspend fun download(choice: ModelChoice) {
    val source = sourceOf(choice)
    val out = file(choice)
    val part = partOf(choice)
    if (out.exists()) return finished()
    dir.mkdirs()
    // one retry from zero when the partial file turns out to belong to another version of the file
    repeat(2) {
      val have = if (part.exists()) part.length() else 0L
      val needed = (choice.sizeGb * 1e9).toLong() - have
      if (dir.usableSpace < needed) throw IOException("พื้นที่ไม่พอ ต้องว่างอีก ${needed / 1_000_000_000 + 1} GB")
      val conn = URL(source.url).openConnection() as HttpURLConnection
      try {
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val decision = DownloadResume.decide(
          DownloadResponseInput(have, conn.responseCode, conn.contentLengthLong, conn.getHeaderField("Content-Range")),
        )
        when (decision) {
          is ResumeDecision.Complete -> return rename(part, out)
          is ResumeDecision.StartOver -> part.delete()
          is ResumeDecision.Failed -> throw IOException("HTTP ${decision.status}")
          is ResumeDecision.Write -> {
            write(conn, part, decision)
            if (!DownloadResume.isComplete(part.length(), decision.totalBytes)) throw IOException("ไฟล์ไม่ครบ")
            return rename(part, out)
          }
        }
      } finally {
        conn.disconnect()
      }
    }
    throw IOException("เซิร์ฟเวอร์ส่งไฟล์ไม่ตรงกัน")
  }

  private suspend fun write(conn: HttpURLConnection, part: File, decision: ResumeDecision.Write) {
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
            _state.value = DownloadState(fraction, paused = false)
          }
        }
      }
    }
  }

  private fun rename(part: File, out: File) {
    if (!part.renameTo(out)) throw IOException("rename failed")
    finished()
  }

  private fun finished() {
    _state.value = DownloadState(1f, paused = false, done = true)
  }

  private fun partOf(choice: ModelChoice) = File(dir, sourceOf(choice).fileName + ".part")

  private data class Source(val fileName: String, val url: String)

  private companion object {
    const val TAG = "HakuModel"
    const val TIMEOUT_MS = 30_000
    const val BUFFER = 256 * 1024

    // Same pins as scripts/get-model.sh. ponytail: E4B on main there too; pin both to the Gallery 1.0.19 commit together.
    const val REV_E2B = "6e5c4f1e395deb959c494953478fa5cec4b8008f"

    fun sourceOf(choice: ModelChoice) = when (choice) {
      ModelChoice.E2B -> "gemma-4-E2B-it.litertlm".let {
        Source(it, "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$REV_E2B/$it?download=true")
      }
      ModelChoice.E4B -> "gemma-4-E4B-it.litertlm".let {
        Source(it, "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/$it?download=true")
      }
    }
  }
}
