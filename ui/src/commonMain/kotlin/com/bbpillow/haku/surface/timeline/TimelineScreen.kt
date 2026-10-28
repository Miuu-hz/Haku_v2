package com.bbpillow.haku.surface.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ConfirmDialog
import com.bbpillow.haku.surface.components.DatePill
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.components.TimelineRow
import com.bbpillow.haku.surface.components.timelineBadge
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

internal val TimelineDots = listOf(HakuColors.Coral, HakuColors.Violet, HakuTone.Amber.deep, HakuColors.Green)

/**
 * Screen 2 of haku-mockup.html (asset C1-C3): date strip, BE header, mini map of the day's stops, the day's diary lines. Tap a line to edit or
 * delete it. State-hoisted: the caller owns [state] (from TimelinePresenter) and applies the callbacks.
 */
@Composable
fun TimelineScreen(
  state: TimelineUiState,
  onSelectDay: (String) -> Unit,
  onEdit: (TimelineLine, String) -> Unit,
  onDelete: (TimelineLine) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var editing by remember { mutableStateOf<TimelineLine?>(null) }
  var deleting by remember { mutableStateOf<TimelineLine?>(null) }

  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { ScreenTitle("ไทม์ไลน์", eyebrow = state.header) }
    item { DateStrip(state, onSelectDay) }
    state.map?.let { item { TimelineMiniMap(it, Modifier.padding(top = 4.dp)) } }
    if (state.lines.isNotEmpty()) {
      item {
        Text(
          "ระบบเขียนเอง แตะรายการเพื่อแก้หรือลบ",
          style = MaterialTheme.typography.labelSmall,
          color = HakuColors.Muted,
          modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        )
      }
    }
    if (state.lines.isEmpty()) {
      item {
        SurfaceCard(Modifier.fillMaxWidth()) {
          Text("วันนี้ยังไม่มีอะไรถูกบันทึก", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
        }
      }
    }
    itemsIndexed(state.lines, key = { _, l -> "${l.memoryDay}/${l.recordId}" }) { i, line ->
      val dot = line.mapNumber?.let(::mapDot) ?: TimelineDots[i % TimelineDots.size]
      TimelineRow(line.time, line.text, dot, line.provenance.timelineBadge(), onClick = { editing = line }, marker = line.mapNumber)
    }
  }

  editing?.let { line ->
    EditLineDialog(
      line,
      onSave = { onEdit(line, it); editing = null },
      onDelete = { deleting = line; editing = null },
      onDismiss = { editing = null },
    )
  }
  deleting?.let { line ->
    ConfirmDialog(
      title = "ลบรายการนี้?",
      message = "ลบแล้ว Haku จะจำไว้ และจะไม่เขียนรายการนี้ขึ้นมาอีก",
      confirmText = "ลบ",
      onConfirm = { onDelete(line); deleting = null },
      onDismiss = { deleting = null },
    )
  }
}

@Composable
private fun DateStrip(state: TimelineUiState, onSelectDay: (String) -> Unit, modifier: Modifier = Modifier) {
  val selected = state.dates.indexOfFirst { it.day == state.day }.takeIf { it >= 0 } ?: state.dates.lastIndex
  val list = rememberLazyListState((selected - 4).coerceAtLeast(0))
  LazyRow(modifier.fillMaxWidth(), state = list, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    items(state.dates, key = { it.day }) { d ->
      DatePill(d.weekday, d.dayOfMonth, d.day == state.day, onClick = { onSelectDay(d.day) })
    }
  }
}

/** Asset C2: text field, black "บันทึก", coral "ลบ" (which then asks for confirmation). */
@Composable
private fun EditLineDialog(line: TimelineLine, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
  var text by remember(line) { mutableStateOf(line.text) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("แก้ไขรายการ ${line.time}", style = MaterialTheme.typography.titleMedium) },
    text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium) },
    confirmButton = { CapsuleButton("บันทึก", { onSave(text) }) },
    dismissButton = {
      Text(
        "ลบ",
        style = MaterialTheme.typography.labelLarge,
        color = HakuColors.Coral,
        modifier = Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onDelete).padding(horizontal = 16.dp, vertical = 12.dp),
      )
    },
    containerColor = HakuColors.Surface,
    shape = HakuRadius.Card,
  )
}
