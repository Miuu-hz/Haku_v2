package com.bbpillow.haku

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.summary.EveningSummarizer
import com.bbpillow.haku.thinking.summary.EveningSummaryInput
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

// ponytail: P2.7 device check, not product UI. Builds the diary for the day, then the evening summary with the real model.
// adb shell am start -n com.bbpillow.haku/.SummarySpikeActivity --es day 2026-10-01 --es model /data/local/tmp/haku/gemma-4-E2B-it.litertlm --es backend gpu --es particle krap
// (no --es model = the app's own model, as the nightly job loads it). Each part's raw model reply is logged as PART.
// then: adb logcat -s HakuSummary   (particle: krap | ka | none; add --ez regenerate false to see the stored-once path)
class SummarySpikeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val day = intent.getStringExtra("day") ?: LocalDate.now().toString()
    val modelPath = intent.getStringExtra("model")
    val backend = if (intent.getStringExtra("backend").equals("cpu", ignoreCase = true)) ModelBackend.Cpu else ModelBackend.Gpu
    val particle = when (intent.getStringExtra("particle")?.lowercase()) {
      "krap" -> EndingParticle.KRAP
      "ka" -> EndingParticle.KA
      else -> EndingParticle.NONE
    }
    val regenerate = intent.getBooleanExtra("regenerate", true)
    thread(name = "summary-spike") {
      val model = LiteRtLocalModel()
      val client = LocalModelClient(PartTap(model))
      try {
        val style = DiaryStyle(particle)
        val tz = ZoneId.systemDefault().rules.getOffset(LocalDate.parse(day).atStartOfDay()).totalSeconds / 60
        // the app's own stores, on the DB thread (one connection, MemoryStore read-modify-write)
        val lines = runBlocking {
          (application as HakuApplication).graph.io {
            diary.compose(DiaryComposeInput(day, tz, style))
            diary.readDay(day, tz).map { it.text }
          }
        }
        Log.i(TAG, "day=$day lines=${lines.size} particle=$particle model=$modelPath backend=$backend")
        lines.forEach { Log.i(TAG, "FACT $it") }
        val cache = if (modelPath?.startsWith("/data/local/tmp") == true) getExternalFilesDir(null)?.absolutePath else null
        val summarizer = EveningSummarizer(client, EveningSummaryStore(com.bbpillow.haku.platform.DataKeys.files, filesDir.resolve("summaries").path.toPath()))
        val out = runBlocking {
          val config = modelPath?.let { ModelConfig(it, backend, cacheDir = cache) } ?: (application as HakuApplication).graph.modelConfig()!!
          if (lines.isNotEmpty()) Log.i(TAG, "loaded in ${client.load(config).loadMillis} ms")
          summarizer.summarize(EveningSummaryInput(day, lines, style, regenerate = regenerate))
        }
        Log.i(TAG, "SOURCE ${out.source} fromStore=${out.fromStore} rejected=${out.rejectedReasons}")
        Log.i(TAG, "METRICS ${out.metrics}")
        Log.i(TAG, "SUMMARY ${out.text}")
        Log.i(TAG, "DONE")
      } catch (t: Throwable) {
        Log.e(TAG, "summary spike failed", t)
      } finally {
        runBlocking { model.unload() }
      }
    }
    finish()
  }

  /** Logs each call's prompt head line and the raw reply, before any check. */
  private class PartTap(private val inner: LocalModel) : LocalModel by inner {
    override fun generate(input: GenerateInput): Flow<String> {
      val reply = StringBuilder()
      return inner.generate(input).onEach { reply.append(it) }
        .onCompletion { Log.i(TAG, "PART ${input.prompt.lineSequence().first().take(40)} -> ${reply.length} chars: $reply") }
    }
  }

  private companion object {
    const val TAG = "HakuSummary"
  }
}
