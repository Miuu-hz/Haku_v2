package com.bbpillow.haku.surface.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.capture.stats.PeriodKind
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * P2.8 statistics: period tabs, the period's measured lines, and the comparison with the previous period. State-hoisted:
 * [state] comes from StatsPresenter. A locked tab or comparison (FREE) opens the existing Haku Plus screen.
 */
@Composable
fun StatsScreen(
  state: StatsUiState,
  onSelect: (PeriodKind) -> Unit,
  onOpenPlus: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item {
      Row(
        Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(HakuIcons.Back, null, tint = HakuColors.Muted, modifier = Modifier.size(20.dp))
        Text("หน้าแรก", style = MaterialTheme.typography.labelLarge, color = HakuColors.Muted)
      }
    }
    item { ScreenTitle("สถิติ", eyebrow = "นับจากที่บันทึกไว้จริงเท่านั้น") }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.tabs.forEach { tab ->
          StatChip(
            if (tab.locked) "${tab.label} · Plus" else tab.label,
            Modifier.clip(HakuRadius.Pill).clickable(role = Role.Tab) { onSelect(tab.kind) },
            dark = tab.kind == state.selected,
          )
        }
      }
    }
    val lines = state.lines
    if (lines == null) {
      item { LockedBoard(onOpenPlus) }
      return@LazyColumn
    }
    item {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Text(state.tabs.first { it.kind == state.selected }.label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
      }
    }
    state.comparison?.let { rows ->
      item {
        SurfaceCard(Modifier.fillMaxWidth()) {
          Text("เทียบกับ${state.previousLabel}", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(6.dp))
          rows.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, modifier = Modifier.padding(vertical = 2.dp)) }
        }
      }
    }
    if (state.comparisonLocked) {
      item {
        StatChip("เทียบกับ${state.previousLabel}ใน Haku Plus", Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onOpenPlus))
      }
    }
  }
}

/** Month/year on FREE: says plainly that nothing is lost, then offers the Plus screen. */
@Composable
private fun LockedBoard(onOpenPlus: () -> Unit) {
  PastelBoard(HakuColors.Lilac, Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      IconTile(HakuIcons.Lock, HakuTone.Violet)
      Text("สถิติรายเดือนและรายปีอยู่ใน Haku Plus", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    }
    Text(
      "ข้อมูลของคุณยังบันทึกอยู่ครบ ไม่มีอะไรถูกล็อกหรือลบ สัปดาห์นี้ดูได้ฟรีตลอด",
      style = MaterialTheme.typography.bodySmall,
      color = HakuColors.Muted,
      modifier = Modifier.padding(top = 10.dp),
    )
    CapsuleButton("ดู Haku Plus", onOpenPlus, Modifier.padding(top = 12.dp))
  }
}
