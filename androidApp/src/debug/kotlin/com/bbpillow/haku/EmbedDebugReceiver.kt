package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import com.bbpillow.haku.platform.LiteRtTextEmbedder
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.thinking.cosine
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * ชิ้น ค spike: embeds the newest [n] diary lines from the memory store with EmbeddingGemma-2 and logs one JSON line
 * to logcat tag `HakuEmbed` (load ms, per-text ms p50/p95, dims, 3 Thai similarity checks):
 * `adb shell am broadcast -n com.bbpillow.haku/.EmbedDebugReceiver -a com.bbpillow.haku.debug.EMBED [--ei n 50] [--es path /data/local/tmp/x.litertlm] [--ez cpu true]`
 * Default path = the model manager's download of [ModelChoice.EMBEDDING_GEMMA_2].
 */
class EmbedDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    if (intent.action != ACTION_EMBED) {
      runCatching { pending.finish() }
      return
    }
    val app = context.applicationContext as HakuApplication
    val n = intent.getIntExtra("n", 50).coerceIn(1, 1000)
    val path = intent.getStringExtra("path") ?: app.graph.downloader.file(ModelChoice.EMBEDDING_GEMMA_2).path
    val cpu = intent.getBooleanExtra("cpu", false)
    app.graph.scope.launch {
      try {
        mutex.withLock { run(app, n, path, cpu) }
      } catch (t: Throwable) {
        Log.e(TAG, "embed failed", t)
      } finally {
        runCatching { pending.finish() }
      }
    }
  }

  private suspend fun run(app: HakuApplication, n: Int, path: String, cpu: Boolean) {
    val json = JSONObject().put("model", path)
    val embedder = LiteRtTextEmbedder(path, cacheDir = app.cacheDir.path, gpu = !cpu)
    try {
      check(File(path).length() > 0L) { "model file missing: $path" }
      val lines = app.graph.io {
        memory.listDays().sortedDescending().asSequence().flatMap { memory.readDay(it).asReversed() }
          .map { it.text }.filter { it.isNotBlank() }.take(n).toList()
      }
      val loadMark = TimeSource.Monotonic.markNow()
      embedder.load()
      json.put("loadMs", loadMark.elapsedNow().inWholeMilliseconds).put("backend", embedder.backend)

      // one text per call so each latency is a single on-device embed, the shape the semantic hook will use
      val perText = lines.map { line ->
        val mark = TimeSource.Monotonic.markNow()
        embedder.embed(listOf(line))
        mark.elapsedNow().inWholeMilliseconds
      }.sorted()
      json.put("n", lines.size)
        .put("p50Ms", percentile(perText, 0.50) ?: JSONObject.NULL)
        .put("p95Ms", percentile(perText, 0.95) ?: JSONObject.NULL)
        .put("avgChars", if (lines.isEmpty()) 0 else lines.sumOf { it.length } / lines.size)

      val pairs = JSONArray()
      for ((anchor, near, far) in SANITY) {
        val (a, b, c) = embedder.embed(listOf(anchor, near, far))
        val simNear = cosine(a, b)
        val simFar = cosine(a, c)
        pairs.put(
          JSONObject().put("anchor", anchor).put("near", near).put("far", far)
            .put("simNear", round3(simNear)).put("simFar", round3(simFar)).put("ok", simNear > simFar),
        )
      }
      json.put("dims", embedder.dims).put("pairs", pairs).put("error", JSONObject.NULL)
    } catch (t: Throwable) {
      json.put("error", "${t::class.simpleName}: ${t.message}")
    } finally {
      embedder.close()
    }
    json.put("nativeHeapMb", (Debug.getNativeHeapAllocatedSize() / MIB * 10).roundToInt() / 10.0)
    Log.i(TAG, json.toString())
  }

  private companion object {
    const val TAG = "HakuEmbed"
    const val ACTION_EMBED = "com.bbpillow.haku.debug.EMBED"
    const val MIB = 1024.0 * 1024.0
    val mutex = Mutex()

    /** anchor, should-be-near, should-be-far */
    val SANITY = listOf(
      Triple("ไปร้านกาแฟ", "แวะคาเฟ่", "ประชุมทีมขาย"),
      Triple("ออกกำลังกายที่ฟิตเนส", "ไปยิมเล่นเวท", "ซื้อผักที่ตลาด"),
      Triple("กินข้าวเย็นกับครอบครัว", "ทานมื้อค่ำกับพ่อแม่", "นั่งรถไฟฟ้าไปทำงาน"),
    )

    /** Nearest-rank percentile of an already sorted list. */
    fun percentile(sorted: List<Long>, p: Double): Long? =
      if (sorted.isEmpty()) null else sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]

    fun round3(v: Float): Double = (v * 1000.0).roundToInt() / 1000.0
  }
}
