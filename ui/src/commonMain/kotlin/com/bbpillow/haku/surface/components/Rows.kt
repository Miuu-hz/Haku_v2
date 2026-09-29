package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.memory.Provenance as MemoryProvenance

/** Badge for a stored record: what the user wrote is theirs, everything the system derived reads "ระบบสรุป". */
fun MemoryProvenance.badge(): Provenance = when (this) {
  MemoryProvenance.USER_TYPED -> Provenance.User
  MemoryProvenance.INFERRED_FROM_USER_DATA, MemoryProvenance.SYSTEM -> Provenance.System
  MemoryProvenance.EXTERNAL -> Provenance.External
}

/** .dates span: one day pill of the timeline date strip, black when selected. */
@Composable
fun DatePill(weekday: String, dayOfMonth: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier
      .widthIn(min = 48.dp)
      .clip(HakuRadius.Small)
      .background(if (selected) HakuColors.Ink else HakuColors.Surface)
      .clickable(role = Role.Tab, onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(weekday, style = MaterialTheme.typography.labelSmall, color = if (selected) HakuColors.Surface.copy(alpha = 0.7f) else HakuColors.Muted)
    Text("$dayOfMonth", style = MaterialTheme.typography.titleMedium, color = if (selected) HakuColors.Surface else HakuColors.Ink)
  }
}

/** .ev: grey time, colored dot, white card with the line and its provenance badge. Tap to edit. */
@Composable
fun TimelineRow(
  time: String,
  text: String,
  dot: Color,
  provenance: Provenance,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(time, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.width(40.dp).padding(top = 14.dp))
    Box(Modifier.padding(top = 17.dp).size(10.dp).background(dot, CircleShape))
    SurfaceCard(
      Modifier.weight(1f).clip(HakuRadius.Card).clickable(role = Role.Button, onClickLabel = "แก้ไขหรือลบ", onClick = onClick),
    ) {
      Text(text, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
      ProvenanceBadge(provenance, Modifier.padding(top = 8.dp))
    }
  }
}

/** .mem-row: title with a grey sub-line, provenance badge and a coral "ลืม" action. Put several in one SurfaceCard. */
@Composable
fun MemoryRow(
  title: String,
  subtitle: String,
  provenance: Provenance,
  onForget: () -> Unit,
  modifier: Modifier = Modifier,
  divider: Boolean = true,
) {
  Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted)
      }
      ProvenanceBadge(provenance)
      Spacer(Modifier.width(8.dp))
      Text(
        "ลืม",
        style = MaterialTheme.typography.labelLarge,
        color = HakuColors.Coral,
        modifier = Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onForget).padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    if (divider) HorizontalDivider(color = HakuColors.Line)
  }
}

/** One UI style confirm: black pill confirms, light pill cancels; optional [content] under the message. */
@Composable
fun ConfirmDialog(
  title: String,
  message: String,
  confirmText: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  content: (@Composable () -> Unit)? = null,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { CapsuleButton(confirmText, onConfirm) },
    dismissButton = { CapsuleButton("ยกเลิก", onDismiss, outline = true) },
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, textAlign = TextAlign.Start)
        content?.invoke()
      }
    },
    containerColor = HakuColors.Surface,
    shape = HakuRadius.Card,
  )
}
