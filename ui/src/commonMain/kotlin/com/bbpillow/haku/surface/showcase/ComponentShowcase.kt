package com.bbpillow.haku.surface.showcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.DonutChart
import com.bbpillow.haku.surface.components.EgressCounter
import com.bbpillow.haku.surface.components.FloatingNav
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.NavItem
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.Provenance
import com.bbpillow.haku.surface.components.ProvenanceBadge
import com.bbpillow.haku.surface.components.ScreenBackdrop
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionHeader
import com.bbpillow.haku.surface.components.SectionLabel
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuTone

private val NavItems = listOf(
  NavItem(HakuIcons.Home, "หน้าแรก"),
  NavItem(HakuIcons.Route, "ไทม์ไลน์"),
  NavItem(HakuIcons.Chat, "แชท"),
  NavItem(HakuIcons.Grid, "ความจำ"),
)

/** Screen 1 of haku-mockup.html (Home Brief, evening) followed by every kit component. Debug only. */
@Composable
fun ComponentShowcase(modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val screenHeight = maxHeight
    ScreenBackdrop(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HomeBriefScreen(Modifier.height(screenHeight))
        KitGallery(Modifier.safeDrawingPadding().padding(horizontal = 20.dp, vertical = 24.dp))
      }
    }
  }
}

@Composable
private fun HomeBriefScreen(modifier: Modifier = Modifier) {
  var tab by remember { mutableIntStateOf(0) }
  Box(modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      ScreenTitle("วันนี้เป็นยังไงบ้าง", eyebrow = "สวัสดีตอนเย็น มิ้นท์")
      HighlightCard(Modifier.fillMaxWidth()) {
        Text("เรื่องของวันนี้", style = MaterialTheme.typography.titleSmall)
        Text(
          "ออกจากบ้าน 08:12 แวะร้านกาแฟหน้าออฟฟิศ ประชุมทีมขายช่วงบ่าย แล้วปิดวันที่ฟิตเนสหนึ่งชั่วโมง",
          style = MaterialTheme.typography.bodyMedium,
          color = HakuColors.Muted,
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          StatChip("ตำแหน่ง 6")
          StatChip("ปฏิทิน 2")
          StatChip("บันทึก 1")
        }
      }
      Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
          DonutChart(0.7f, Modifier.align(Alignment.CenterHorizontally))
          Text("เวลานอกบ้าน 9 ชม.", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        PastelBoard(HakuColors.Butter, Modifier.weight(1f).fillMaxHeight()) {
          IconTile(HakuIcons.Walk, HakuTone.Amber)
          Spacer(Modifier.weight(1f))
          Text("7,420", style = MaterialTheme.typography.titleLarge)
          Text("ก้าว · เดิน 32 นาที", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
      PastelBoard(HakuColors.Lilac, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          IconTile(HakuIcons.Book, HakuTone.Violet)
          Column {
            Text("เรื่องน่ารู้วันนี้", style = MaterialTheme.typography.titleSmall)
            Text("ทำไมเดินหลังอาหารช่วยเรื่องน้ำตาล", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
          }
        }
      }
    }
    FloatingNav(NavItems, tab, { tab = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
  }
}

@Composable
private fun KitGallery(modifier: Modifier = Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("Component kit", style = MaterialTheme.typography.headlineMedium)

    SectionLabel("Capsule buttons")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      CapsuleButton("ใช้แบบนี้", {})
      CapsuleButton("คัดลอก", {}, outline = true)
    }

    SectionLabel("Surface / highlight card")
    SurfaceCard(Modifier.fillMaxWidth()) {
      Text("คิวงานคืนนี้", style = MaterialTheme.typography.titleSmall)
      Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ถอดความประชุมทีมขาย", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatChip("กำลังทำ", dark = true)
      }
      Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ย่อยความจำ (Dream)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatChip("รอ")
      }
    }
    HighlightCard(Modifier.fillMaxWidth()) {
      Text("ตัวอย่าง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      Text("มิ้นท์ วันนี้มีสามนัดนะคะ นัดแรก 09:30", style = MaterialTheme.typography.bodyMedium)
    }

    SectionLabel("Pastel boards + icon tiles")
    val boards = listOf(
      Triple(HakuColors.Butter, HakuTone.Amber, HakuIcons.File),
      Triple(HakuColors.Sky, HakuTone.Blue, HakuIcons.Box),
      Triple(HakuColors.Lilac, HakuTone.Violet, HakuIcons.Book),
      Triple(HakuColors.Peach, HakuTone.Coral, HakuIcons.Lock),
      Triple(HakuColors.Mint, HakuTone.Mint, HakuIcons.Temp),
    )
    boards.chunked(2).forEach { pair ->
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        pair.forEach { (color, tone, icon) ->
          PastelBoard(color, Modifier.weight(1f)) {
            IconTile(icon, tone)
            Spacer(Modifier.height(10.dp))
            Text(tone.name, style = MaterialTheme.typography.titleLarge)
            Text("HakuTone.${tone.name}", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
          }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }

    SectionLabel("Chips, provenance, egress")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      StatChip("ในเครื่อง · Gemma 4")
      StatChip("ทุกอย่างอยู่ในเครื่อง", dark = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Provenance.entries.forEach { ProvenanceBadge(it) }
    }
    EgressCounter(0)

    SectionLabel("Headers")
    Column {
      ScreenTitle("สิ่งที่ Haku จำ", eyebrow = "เปิดดูและลบได้ทุกบรรทัด")
      SectionHeader("ไฟล์ในเครื่องบ้าน", trailing = "สื่อ + เอกสาร")
    }

    SectionLabel("Donut chart")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      DonutChart(0.25f, size = 80.dp)
      DonutChart(0.7f)
      DonutChart(0.95f, size = 140.dp)
    }

    SectionLabel("Floating nav")
    var tab by remember { mutableIntStateOf(2) }
    FloatingNav(NavItems, tab, { tab = it })
    Spacer(Modifier.size(24.dp))
  }
}
