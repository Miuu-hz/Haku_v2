package com.bbpillow.haku

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bbpillow.haku.demo.DemoSeed
import com.bbpillow.haku.demo.DemoSeedOutput
import com.bbpillow.haku.platform.capture.CaptureController
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEBUG ONLY: two weeks of the "มิ้นท์" persona (core/demo/DemoSeed) into the real stores. The release source set has a
 * no-op [DemoSeedButton] and no seeding code. Idempotent; "ลืมทุกอย่าง" removes it all.
 */
suspend fun AppGraph.seedDemo(): DemoSeedOutput = withContext(CaptureController.dispatcher) {
  val d = onCaptureThread()
  DemoSeed(d.events, d.places, d.memory, d.diary, d.summaries, d.profile, d.dreamEngine())
    .seed(System.currentTimeMillis(), tzOffsetMinutes)
    .also { Log.i(CaptureController.TAG, "DEMO_SEED $it") }
}

/** "โหลดข้อมูลสาธิต" as a small debug chip: seeds, then [onSeeded] (the caller reloads its screen or leaves onboarding). */
@Composable
fun DemoSeedButton(graph: AppGraph, onSeeded: () -> Unit, modifier: Modifier = Modifier) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var busy by remember { mutableStateOf(false) }
  val seed = {
    if (!busy) {
      busy = true
      scope.launch {
        try {
          val out = graph.seedDemo()
          if (out.alreadySeeded) Toast.makeText(context, "มีข้อมูลสาธิตอยู่แล้ว", Toast.LENGTH_SHORT).show()
          onSeeded()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(CaptureController.TAG, "demo seed failed", e)
          Toast.makeText(context, "โหลดข้อมูลสาธิตไม่สำเร็จ", Toast.LENGTH_LONG).show()
        } finally {
          busy = false
        }
      }
    }
  }
  Text(
    if (busy) "กำลังโหลด…" else "DEBUG · โหลดข้อมูลสาธิต",
    style = MaterialTheme.typography.labelSmall,
    color = HakuColors.Muted,
    modifier = modifier
      .clip(HakuRadius.Pill)
      .background(HakuColors.Surface.copy(alpha = 0.7f))
      .border(1.dp, HakuColors.Line, HakuRadius.Pill)
      .clickable(role = Role.Button) { seed() }
      .padding(horizontal = 10.dp, vertical = 5.dp),
  )
}
