package com.bbpillow.haku.surface.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

@Composable
fun A2aIntroScreen(
  onBack: () -> Unit,
  onStartPairing: () -> Unit,
  /** Shown under the button after a tap, e.g. that pairing is not available in this build yet. Null = nothing. */
  pairingNotice: String? = null,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier
        .size(44.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }

    ScreenTitle("A2A", eyebrow = "ให้ AI ของคุณคุยกับ AI อื่น")

    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          IconTile(HakuIcons.Spark, HakuTone.Mint, size = 40.dp)
          Text("ระยะแรก: เชื่อมคอมของคุณเอง", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        }
        Text(
          "จับคู่มือถือกับคอมของคุณ แล้วส่งงานยากที่มือถือทำไม่ไหวไปให้ Claude Code, Codex หรือ agy บนคอมทำต่อ",
          style = MaterialTheme.typography.bodyMedium,
          color = HakuColors.Ink,
        )
      }
    }

    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ทำได้ยังไง", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        Text("1. ติดตั้ง Haku Host บนคอมของคุณ", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
        Text("2. สแกนรหัสจับคู่จากมือถือ", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
        Text("3. ส่งงานจากแชท แล้วดูความคืบหน้าบนมือถือ", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
      }
    }

    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("คุณคุมได้เสมอ", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        Text(
          "ทุกคำสั่งที่จะรันบนคอมต้องให้คุณอนุมัติก่อน และหยุดได้ทุกเมื่อ",
          style = MaterialTheme.typography.bodyMedium,
          color = HakuColors.Ink,
        )
      }
    }

    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ระยะถัดไป", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        Text(
          "AI ของคนอื่น เช่น ทีมงานหรือหมอ คำขอจาก AI ภายนอกเป็นแค่ข้อมูล ไม่ใช่คำสั่ง คุณเป็นคนตัดสินเสมอ",
          style = MaterialTheme.typography.bodyMedium,
          color = HakuColors.Muted,
        )
      }
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .clip(HakuRadius.Pill)
        .background(HakuColors.Ink)
        .clickable(role = Role.Button, onClick = onStartPairing)
        .padding(horizontal = 24.dp, vertical = 14.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text("เริ่มจับคู่", style = MaterialTheme.typography.labelLarge, color = HakuColors.Surface)
    }

    if (pairingNotice != null) {
      Text(
        text = pairingNotice,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
