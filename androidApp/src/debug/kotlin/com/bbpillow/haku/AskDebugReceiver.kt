package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.util.Log
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.surface.chat.ChatAnswerOutput
import com.bbpillow.haku.surface.chat.ChatAnswerSource
import com.bbpillow.haku.surface.chat.ChatQuestionInput
import com.bbpillow.haku.thinking.GenerateEvent
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.GenerateMetrics
import com.bbpillow.haku.thinking.LocalModel
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.context.RetrievalTier
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only receiver that exercises the Chat tab's exact `askHaku` pipeline over adb and logs one JSON line per
 * question to logcat tag `HakuAsk`:
 * `adb shell am broadcast -n com.bbpillow.haku/.AskDebugReceiver -a com.bbpillow.haku.debug.ASK --es q "<question>" [--es id "<tag>"] [--ez warm true]`
 * `adb shell am broadcast -n com.bbpillow.haku/.AskDebugReceiver -a com.bbpillow.haku.debug.ASK_UNLOAD`
 */
class AskDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.applicationContext as HakuApplication
    // No goAsync: model loads and indexing outlive the 60 s broadcast limit (ANR kill); the app scope runs them.
    when (intent.action) {
      ACTION_UNLOAD -> app.graph.scope.launch {
        try {
          mutex.withLock { unloadWarm() }
        } catch (t: Throwable) {
          Log.e(TAG, "unload failed", t)
      }
      }
      ACTION_ASK -> {
        val question = intent.getStringExtra("q").orEmpty()
        val id = intent.getStringExtra("id") ?: System.currentTimeMillis().toString()
        val warm = intent.getBooleanExtra("warm", false)
        val semantic = intent.getBooleanExtra("semantic", false)
        app.graph.scope.launch {
          try {
            mutex.withLock { runAsk(app, id, question, warm, semantic) }
          } catch (t: Throwable) {
            Log.e(TAG, "ask failed", t)
          }
        }
      }
      // the Chat tab's own askHaku (mentions, picker, skill turns); logs the answer and any gate card it raised
      ACTION_ASK_FULL -> {
        val question = intent.getStringExtra("q").orEmpty()
        app.graph.scope.launch {
          try {
            val t0 = System.currentTimeMillis()
            val a = mutex.withLock { askHaku(app.graph, question) }
            val p = a?.actionId?.let { id -> app.graph.actionGate.proposals.value.firstOrNull { it.id == id } }
            Log.i(TAG, org.json.JSONObject().put("q", question).put("ms", System.currentTimeMillis() - t0).put("text", a?.text)
              .put("source", a?.source?.name).put("citations", a?.citations?.size).put("appointment", a?.appointment?.let { "${it.title} ${it.dayLabel} ${it.timeLabel}" })
              .put("card", p?.let { "${it.request.action} | ${it.request.title} | ${it.request.detail} | ${it.request.origin} | ${it.status}" })
              .toString())
          } catch (t: Throwable) {
            Log.e(TAG, "ask full failed", t)
          }
        }
      }
      else -> Unit
    }
  }

  private suspend fun runAsk(app: HakuApplication, id: String, question: String, warm: Boolean, forceSemantic: Boolean) {
    val graph = app.graph
    var loadMs: Long? = null
    var metrics: GenerateMetrics? = null
    var fallbackTtftMs: Long? = null
    var fallbackTotalMs: Long? = null
    var backend: String? = null
    var error: String? = null
    var semanticOn = false
    var semanticLines = 0

    // P5.3 as MainShell.askHaku: "ถ้า … ให้เตือน …" is stored and confirmed, not retrieved
    val intent = runCatching { graph.io { intentCommand.tryHandle(question, System.currentTimeMillis()) } }.getOrNull()
    val answer: ChatAnswerOutput = intent ?: try {
      // as MainShell.askHaku, but waits for a cold embedder so every adb run uses the semantic tier when it can
      val semantic = graph.questionSemantic(question, waitForLoad = true, force = forceSemantic)
      val (presenter, retrieval) = graph.io {
        chat to chat.retrieve(ChatQuestionInput(question, System.currentTimeMillis(), profile.load().style), semantic)
      }
      semanticLines = retrieval.context.items.count { RetrievalTier.SEMANTIC in it.tiers && it.tiers.size == 1 }
      semanticOn = semantic != null
      val config = try {
        graph.modelConfig()
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        error = "${t::class.simpleName}: ${t.message}"
        null
      }
      if (config == null) {
        retrieval.fallback
      } else {
        val reuse = warm && warmModel != null && warmConfig == config
        if (!reuse && warmModel != null) unloadWarm()
        val model = if (reuse) warmModel!! else LiteRtLocalModel()
        var loaded = reuse
        try {
          val inner = LocalModelClient(model)
          if (!reuse) {
            loadMs = inner.load(config).loadMillis
            loaded = true
            if (warm) {
              warmModel = model
              warmConfig = config
            }
          } else {
            loadMs = 0L
          }
          var genError: Throwable? = null
          // ponytail: tap LocalModelClient's own GenerateMetrics without changing ChatPresenter.answer
          val tap = object : LocalModel {
            override suspend fun load(config: ModelConfig) = model.load(config)
            override fun generate(input: GenerateInput): Flow<String> = flow {
              val mark = TimeSource.Monotonic.markNow()
              try {
                inner.generate(input).collect { e ->
                  when (e) {
                    is GenerateEvent.Token -> {
                      if (fallbackTtftMs == null) fallbackTtftMs = mark.elapsedNow().inWholeMilliseconds
                      emit(e.text)
                    }
                    is GenerateEvent.Finished -> metrics = e.output.metrics
                  }
                }
              } catch (t: Throwable) {
                if (t !is CancellationException) genError = t
                throw t
              } finally {
                fallbackTotalMs = mark.elapsedNow().inWholeMilliseconds
              }
            }
            override fun cancel() = inner.cancel()
            override suspend fun unload() = model.unload()
          }
          val out = presenter.answer(retrieval, LocalModelClient(tap))
          backend = usedBackend(model, config)
          if (genError != null) {
            error = "${genError!!::class.simpleName}: ${genError!!.message}"
            if (warm) unloadWarm()
          }
          out
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          error = "${t::class.simpleName}: ${t.message}"
          backend = usedBackend(model, config)
          if (warm || !loaded) unloadWarm()
          retrieval.fallback
        } finally {
          if (!warm) withContext(NonCancellable) { runCatching { model.unload() } }
        }
      }
    } catch (t: Throwable) {
      error = "${t::class.simpleName}: ${t.message}"
      ChatAnswerOutput(question, "ค้นบันทึกไม่สำเร็จ ลองใหม่อีกครั้ง", emptyList(), ChatAnswerSource.RETRIEVAL)
    }

    val rt = Runtime.getRuntime()
    val javaHeapMb = round1((rt.totalMemory() - rt.freeMemory()) / MIB)
    val nativeHeapMb = round1(Debug.getNativeHeapAllocatedSize() / MIB)
    val sticky = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val batteryTempC = if (tempTenths > 0) tempTenths / 10.0 else null

    val json = JSONObject()
      .put("id", id)
      .put("question", question)
      .put("answer", answer.text)
      .put("source", answer.source.name)
      .put("citations", JSONArray(answer.citations.map { "${it.label}: ${it.text}" }))
      .put("rejectedReasons", JSONArray(answer.rejectedReasons))
      .put("rejectedText", answer.rejectedText ?: JSONObject.NULL)
      .put("backend", backend ?: JSONObject.NULL)
      .put("loadMs", loadMs ?: JSONObject.NULL)
      .put("ttftMs", (metrics?.timeToFirstTokenMillis ?: fallbackTtftMs) ?: JSONObject.NULL)
      .put("totalMs", (metrics?.totalMillis ?: fallbackTotalMs) ?: JSONObject.NULL)
      .put("tokensPerSec", metrics?.tokensPerSecond?.let { (it * 100.0).roundToInt() / 100.0 } ?: JSONObject.NULL)
      .put("javaHeapMb", javaHeapMb)
      .put("nativeHeapMb", nativeHeapMb)
      .put("batteryTempC", batteryTempC ?: JSONObject.NULL)
      .put("semantic", semanticOn)
      .put("semanticLines", semanticLines)
      .put("error", error ?: JSONObject.NULL)
    Log.i(TAG, json.toString())
  }

  private companion object {
    const val TAG = "HakuAsk"
    const val ACTION_ASK = "com.bbpillow.haku.debug.ASK"
    const val ACTION_UNLOAD = "com.bbpillow.haku.debug.ASK_UNLOAD"
    const val ACTION_ASK_FULL = "com.bbpillow.haku.debug.ASK_FULL"
    const val MIB = 1024.0 * 1024.0

    val mutex = Mutex()
    var warmModel: LiteRtLocalModel? = null
    var warmConfig: ModelConfig? = null

    suspend fun unloadWarm() {
      val m = warmModel
      warmModel = null
      warmConfig = null
      if (m != null) withContext(NonCancellable) { runCatching { m.unload() } }
    }

    fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0

    // ponytail: reflect backend if LiteRtLocalModel exposes one, else report config.backend
    fun usedBackend(model: LiteRtLocalModel?, config: ModelConfig?): String? {
      if (model == null) return null
      val getter = runCatching {
        model.javaClass.methods.firstOrNull {
          it.parameterCount == 0 && (it.returnType == ModelBackend::class.java || it.name.contains("Backend", ignoreCase = true))
        }?.invoke(model)?.toString()
      }.getOrNull()
      val field = getter ?: runCatching {
        model.javaClass.declaredFields.firstOrNull {
          it.type == ModelBackend::class.java || it.name.contains("backend", ignoreCase = true)
        }?.apply { isAccessible = true }?.get(model)?.toString()
      }.getOrNull()
      return field ?: config?.backend?.name
    }
  }
}
