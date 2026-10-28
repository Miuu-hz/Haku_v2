package com.bbpillow.haku.surface.intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.ConfirmDialog
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** P5.3 "ความตั้งใจค้างไว้": every standing intention with its rule, each one cancellable. State-hoisted. */
@Composable
fun IntentListScreen(
  state: IntentListUiState,
  onCancel: (IntentItem) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var cancelling by remember { mutableStateOf<IntentItem?>(null) }
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Box(
        Modifier.size(36.dp).clip(HakuRadius.Pill).clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
        contentAlignment = Alignment.Center,
      ) {
        Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
      }
    }
    item { ScreenTitle("ความตั้งใจค้างไว้", eyebrow = "พิมพ์ในแชต เช่น “ถ้าถึงที่ทำงานให้เตือนส่งรายงาน”") }
    item {
      SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
        if (state.items.isEmpty()) {
          Text("ยังไม่มีรายการ", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(vertical = 12.dp))
        }
        state.items.forEachIndexed { i, item ->
          Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(item.title, style = MaterialTheme.typography.bodyMedium, color = if (item.active) HakuColors.Ink else HakuColors.Muted)
              Text(item.detail, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted)
            }
            Text(
              "ยกเลิก",
              style = MaterialTheme.typography.labelLarge,
              color = HakuColors.Coral,
              modifier = Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button) { cancelling = item }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
          if (i < state.items.lastIndex) HorizontalDivider(color = HakuColors.Line)
        }
      }
    }
  }
  cancelling?.let { item ->
    ConfirmDialog(
      title = "ยกเลิกรายการนี้?",
      message = "${item.title} จะถูกลบจากเครื่องทันที และจะไม่เตือนอีก",
      confirmText = "ยกเลิกรายการ",
      onConfirm = { onCancel(item); cancelling = null },
      onDismiss = { cancelling = null },
    )
  }
}
