package com.bbpillow.haku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTheme

// ponytail: placeholder screen to prove the build and theme on device; replaced by P0.4 spike screens.
// ponytail: TimelineScreen/TransparencyScreen (P3.2/P3.4) are not wired here yet; they only run on fake data in the
// debug ShowcaseActivity. Wire them with TimelinePresenter(DiaryComposer, MemoryStore, tz) and
// TransparencyPresenter(MemoryStore, tz) once app navigation exists; call the presenters off the main thread (file IO).
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      HakuTheme {
        Column(
          Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text("Haku", style = MaterialTheme.typography.displayLarge)
          Surface(shape = HakuRadius.Card, color = HakuColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("บันทึกชีวิตให้เองโดยไม่ต้องสั่ง", style = MaterialTheme.typography.titleMedium)
              Text("โครง KMP พร้อมแล้ว · P0.4 กำลังเริ่ม", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
            }
          }
          Box(Modifier.background(HakuColors.Gradient, HakuRadius.Pill).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text("ธีมจาก haku-mockup.html", style = MaterialTheme.typography.labelLarge, color = Color.White)
          }
        }
      }
    }
  }
}
