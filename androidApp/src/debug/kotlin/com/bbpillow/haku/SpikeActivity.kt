package com.bbpillow.haku

import android.os.Bundle
import android.os.Debug
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.platform.LiteRtLocalModel
import com.bbpillow.haku.surface.theme.HakuTheme
import com.bbpillow.haku.thinking.GenerateEvent
import com.bbpillow.haku.thinking.GenerateInput
import com.bbpillow.haku.thinking.LocalModelClient
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig

private const val TAG = "HakuSpike"
private const val DEFAULT_MAX_TOKENS = 2048
private const val LONG_PROMPT_LINE = "วันนี้ไปตลาดซื้อผัก ประชุมทีมตอนบ่ายสอง และโทรหาแม่ตอนเย็น "
private val PROMPTS = listOf(
  "สวัสดี ช่วยแนะนำตัวสั้นๆ หน่อย",
  "สรุปเป็นข้อๆ: วันนี้ไปตลาดซื้อผัก ประชุมทีมตอนบ่ายสอง และโทรหาแม่ตอนเย็น",
  "อธิบายว่าทำไมท้องฟ้าถึงเป็นสีฟ้า ภายในสามประโยค",
)

// ponytail: spike-only runner (P0.4 ก), launched via adb, not part of the product UI.
// adb shell am start -n com.bbpillow.haku/.SpikeActivity --es model /data/local/tmp/haku/gemma-4-E2B-it.litertlm --es backend gpu [--ez mtp true] [--ei maxTokens 8192]
class SpikeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val explicitPath = intent.getStringExtra("model")
    val backend = if (intent.getStringExtra("backend").equals("cpu", ignoreCase = true)) ModelBackend.Cpu else ModelBackend.Gpu
    val mtp = intent.getBooleanExtra("mtp", false)
    val maxTokens = intent.getIntExtra("maxTokens", DEFAULT_MAX_TOKENS)
    setContent {
      val lines = remember { mutableStateListOf<String>() }
      fun say(s: String) { Log.i(TAG, s); lines += s }
      LaunchedEffect(Unit) {
        val client = LocalModelClient(LiteRtLocalModel())
        // no --es model: the file onboarding downloaded (AppGraph.modelConfig), else the adb-pushed one
        val modelPath = explicitPath ?: (application as HakuApplication).graph.modelConfig()?.modelPath
          ?: "/data/local/tmp/haku/gemma-4-E2B-it.litertlm"
        try {
          say("model=$modelPath backend=$backend mtp=$mtp maxTokens=$maxTokens device=${android.os.Build.MODEL}")
          val cache = if (modelPath.startsWith("/data/local/tmp")) getExternalFilesDir(null)?.absolutePath else null
          val load = client.load(ModelConfig(modelPath, backend, maxTokens = maxTokens, cacheDir = cache, speculativeDecoding = mtp))
          say("LOAD ms=${load.loadMillis} pssMB=${pssMb()}")
          (PROMPTS + longPrompt(maxTokens)).forEachIndexed { i, prompt ->
            say("PROMPT ${i + 1}: $prompt")
            client.generate(GenerateInput(prompt)).collect { e ->
              if (e is GenerateEvent.Finished) {
                val m = e.output.metrics
                say("REPLY ${i + 1}: ${e.output.text}")
                say("METRICS ${i + 1}: ttftMs=${m.timeToFirstTokenMillis} totalMs=${m.totalMillis} chunks=${m.chunkCount} tokPerSec=${m.tokensPerSecond?.let { "%.2f".format(it) }} chars=${e.output.text.length} pssMB=${pssMb()}")
              }
            }
          }
          say("DONE")
        } catch (t: Throwable) {
          Log.e(TAG, "spike failed", t)
          say("ERROR: ${t::class.simpleName}: ${t.message}")
        } finally {
          client.unload()
        }
      }
      HakuTheme {
        Text(
          lines.joinToString("\n\n"),
          Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        )
      }
    }
  }

  private fun pssMb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024

  // ponytail: one char per context token keeps Thai under the limit while showing how prefill grows; not an exact token count.
  private fun longPrompt(maxTokens: Int): String =
    "สรุปข้อความนี้ในหนึ่งประโยค: " + LONG_PROMPT_LINE.repeat(maxTokens / LONG_PROMPT_LINE.length)
}
