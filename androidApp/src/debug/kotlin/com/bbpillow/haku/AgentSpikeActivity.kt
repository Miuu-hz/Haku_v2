package com.bbpillow.haku

import android.os.Bundle
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
import com.bbpillow.haku.platform.agent.LiteRtAgentSession
import com.bbpillow.haku.platform.agent.ToolDefinition
import com.bbpillow.haku.platform.agent.ToolExecutionContext
import com.bbpillow.haku.surface.theme.HakuTheme
import com.bbpillow.haku.thinking.AgentConfig
import com.bbpillow.haku.thinking.AgentInput
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelConfig
import com.bbpillow.haku.thinking.execute
import com.google.ai.edge.litertlm.Tool
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "HakuAgent"
private const val PROMPT = "ตอนนี้กี่โมงแล้ว ช่วยดูเวลาให้หน่อย"
private const val SYSTEM = "You are Haku, a Thai personal assistant. Reply in Thai. Use tools when they help answer."

class CurrentTimeTool : ToolDefinition {
  override var executionContext: ToolExecutionContext? = null

  @Tool(description = "Returns the current local date and time on the phone.")
  fun getCurrentTime(): Map<String, String> = runTool("getCurrentTime", emptyMap()) {
    mapOf("time" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
  }
}

// ponytail: P1.2 agent chat check, launched via adb, not product UI.
// adb shell am start -n com.bbpillow.haku/.AgentSpikeActivity --es model /data/local/tmp/haku/gemma-4-E2B-it.litertlm --es backend gpu
class AgentSpikeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val modelPath = intent.getStringExtra("model") ?: "/data/local/tmp/haku/gemma-4-E2B-it.litertlm"
    val backend = if (intent.getStringExtra("backend").equals("cpu", ignoreCase = true)) ModelBackend.Cpu else ModelBackend.Gpu
    setContent {
      val lines = remember { mutableStateListOf<String>() }
      fun say(s: String) { Log.i(TAG, s); lines += s }
      LaunchedEffect(Unit) {
        val agent = LiteRtAgentSession(listOf(CurrentTimeTool()))
        try {
          say("model=$modelPath backend=$backend")
          val cache = if (modelPath.startsWith("/data/local/tmp")) getExternalFilesDir(null)?.absolutePath else null
          agent.start(AgentConfig(ModelConfig(modelPath, backend, cacheDir = cache), SYSTEM))
          say("PROMPT: $PROMPT")
          val out = agent.execute(AgentInput(PROMPT))
          out.toolCalls.forEach { say("TOOL ${it.name} args=${it.arguments} result=${it.result}") }
          say("TOOL_CALLS: ${out.toolCalls.size}")
          say("REPLY: ${out.text}")
          say("DONE")
        } catch (t: Throwable) {
          Log.e(TAG, "agent spike failed", t)
          say("ERROR: ${t::class.simpleName}: ${t.message}")
        } finally {
          agent.close()
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
}
