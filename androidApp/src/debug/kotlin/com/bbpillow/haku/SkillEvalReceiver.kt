package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bbpillow.haku.platform.LiteRtTextEmbedder
import com.bbpillow.haku.skill.BuiltInSkills
import com.bbpillow.haku.skill.EmbeddingSkillPicker
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Evaluates [EmbeddingSkillPicker] against `assets/skill-eval.tsv` with the on-device EmbeddingGemma model.
 * `adb shell am broadcast -n com.bbpillow.haku/.SkillEvalReceiver -a com.bbpillow.haku.debug.SKILL_EVAL`
 */
class SkillEvalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    // No goAsync: model load + 52 embeds can outlive the broadcast limit; a DebugJob keeps the process unfrozen.
    val app = context.applicationContext as HakuApplication
    DebugJob.start(context) {
      try {
        mutex.withLock { run(context.applicationContext, app) }
      } catch (t: Throwable) {
        Log.e(TAG, "skill eval failed", t)
      }
    }
  }

  private suspend fun run(context: Context, app: HakuApplication) {
    val path = app.graph.embeddingModelPath()
    if (path == null) {
      Log.e(TAG, JSONObject().put("error", "no embedding model").toString())
      return
    }
    val pairs = context.assets.open("skill-eval.tsv").bufferedReader().useLines { lines ->
      lines.drop(1).filter { it.isNotBlank() }.map { line ->
        val parts = line.split('\t', limit = 2)
        parts[0].trim() to parts.getOrElse(1) { "" }.trim()
      }.toList()
    }
    val embedder = LiteRtTextEmbedder(path, cacheDir = context.cacheDir.path)
    try {
      val picker = EmbeddingSkillPicker(embedder)
      val skills = BuiltInSkills.ALL
      val results = pairs.map { (expected, text) ->
        val picked = picker.pick(text, skills)?.name ?: CHAT
        val ranked = picker.rank(text, skills)
        val top = ranked.firstOrNull()?.second ?: -1f
        val second = ranked.getOrNull(1)?.second ?: -1f
        val chat = picker.anchorScore(text)
        val margin = top - maxOf(second, chat)
        Log.i(
          TAG,
          JSONObject()
            .put("expected", expected)
            .put("picked", picked)
            .put("ok", picked == expected)
            .put("topScore", round3(top))
            .put("secondScore", round3(second))
            .put("chatScore", round3(chat))
            .put("margin", round3(margin))
            .put("text", text)
            .toString(),
        )
        Row(expected, picked, margin)
      }
      val chatRows = results.filter { it.expected == CHAT }
      val skillRows = results.filter { it.expected != CHAT }
      val perSkill = JSONObject()
      for (s in skills) {
        val tp = results.count { it.expected == s.name && it.picked == s.name }
        val fp = results.count { it.expected != s.name && it.picked == s.name }
        val fn = results.count { it.expected == s.name && it.picked != s.name }
        perSkill.put(
          s.name,
          JSONObject().put("precision", rate(tp, tp + fp)).put("recall", rate(tp, tp + fn)),
        )
      }
      val meanMargin = if (results.isEmpty()) 0.0 else round3(results.map { it.margin }.average().toFloat())
      Log.i(
        TAG,
        JSONObject()
          .put("summary", true)
          .put("total", results.size)
          .put("accuracy", rate(results.count { it.expected == it.picked }, results.size))
          .put("perSkill", perSkill)
          .put("chatFpRate", rate(chatRows.count { it.picked != CHAT }, chatRows.size))
          .put("skillMissRate", rate(skillRows.count { it.picked != it.expected }, skillRows.size))
          .put("meanMargin", meanMargin)
          .toString(),
      )
    } finally {
      embedder.close()
    }
  }

  private data class Row(val expected: String, val picked: String, val margin: Float)

  private companion object {
    const val TAG = "HakuSkillEval"
    const val ACTION = "com.bbpillow.haku.debug.SKILL_EVAL"
    const val CHAT = "chat"
    val mutex = Mutex()

    fun round3(v: Float): Double = (v * 1000.0).roundToInt() / 1000.0
    fun rate(num: Int, den: Int): Double = if (den == 0) 0.0 else (num * 1000.0 / den).roundToInt() / 1000.0
  }
}
