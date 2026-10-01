package com.bbpillow.haku.surface.node

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionHeader
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * Screen 6 of haku-mockup.html in its honest "coming soon" state: the home node is P7, nothing is connected, so every
 * number reads "—" and every queue row says where that work runs today. Same layout as the mockup (two pastel boards,
 * tonight's queue, file tiles) so it fills in place when P7 lands.
 */
// ponytail: static on purpose; take a HomeNodeUiState when the P7 node reports temperature, queue and files.
@Composable
fun HomeNodeScreen(onBack: () -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item {
      Row(
        Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(HakuIcons.Back, null, tint = HakuColors.Muted, modifier = Modifier.size(20.dp))
        Text("ความจำ", style = MaterialTheme.typography.labelLarge, color = HakuColors.Muted)
      }
    }
    item { ScreenTitle("เครื่องที่บ้าน", Modifier.padding(top = 0.dp), eyebrow = "เชื่อมในวงเครือข่ายบ้าน") }
    item {
      HighlightCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("ยังไม่ได้เชื่อม", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
          StatChip("เร็ว ๆ นี้", dark = true)
        }
        Text(
          "คอมพิวเตอร์ที่บ้านจะรับงานหนักตอนกลางคืน เช่น ถอดความเสียง และโมเดลใหญ่ " +
            "ผ่าน Wi-Fi บ้านเท่านั้น ไม่ขึ้นคลาวด์ ตอนนี้ทุกอย่างยังทำในมือถือเครื่องนี้",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
    }
    item {
      Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StatBoard(HakuColors.Mint, HakuIcons.Temp, HakuTone.Mint, "อุณหภูมิเครื่อง", Modifier.weight(1f))
        StatBoard(HakuColors.Sky, HakuIcons.Box, HakuTone.Blue, "งานในคิว", Modifier.weight(1f))
      }
    }
    item {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Text("คิวงานคืนนี้", style = MaterialTheme.typography.titleSmall)
        QueueRow("ถอดความเสียงประชุม", "ยังไม่มี")
        QueueRow("ย่อยความจำ (Dream)", "ทำในมือถือ", dark = true)
        QueueRow("โมเดลใหญ่", "ยังไม่โหลด")
      }
    }
    item { SectionHeader("ไฟล์ในเครื่องบ้าน", trailing = "สื่อ + เอกสาร") }
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FileTile(HakuIcons.File, HakuTone.Amber, "เอกสาร", Modifier.weight(1f))
        FileTile(HakuIcons.Img, HakuTone.Violet, "รูปภาพ", Modifier.weight(1f))
        FileTile(HakuIcons.File, HakuTone.Blue, "ไฟล์อื่น", Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun StatBoard(color: androidx.compose.ui.graphics.Color, icon: ImageVector, tone: HakuTone, label: String, modifier: Modifier) {
  PastelBoard(color, modifier.fillMaxHeight()) {
    IconTile(icon, tone)
    Spacer(Modifier.height(12.dp))
    Text("—", style = MaterialTheme.typography.titleLarge, color = HakuColors.Muted)
    Text(label, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
  }
}

@Composable
private fun QueueRow(label: String, status: String, dark: Boolean = false) {
  Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    StatChip(status, dark = dark)
  }
}

@Composable
private fun FileTile(icon: ImageVector, tone: HakuTone, label: String, modifier: Modifier) {
  SurfaceCard(modifier, padding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
      IconTile(icon, tone, size = 40.dp)
      Text(label, style = MaterialTheme.typography.labelSmall, color = HakuColors.Ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
      Text("ยังว่าง", style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, textAlign = TextAlign.Center)
    }
  }
}
