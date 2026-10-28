package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

/** Timeline: system-written lines carry no badge (every line would say the same); typed or outside lines do. */
fun MemoryProvenance.timelineBadge(): Provenance? = badge().takeIf { it != Provenance.System }

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

/**
 * .ev: grey time, colored dot, compact white card (h5 title + grey sub-line); the trip that led here sits above the card
 * like the mockup's .travel line. [provenance] = null for lines the system wrote (the common case, no badge); pass a badge
 * only for what the user typed or what came from outside. Tap to edit.
 */
@Composable
fun TimelineRow(
  time: String,
  text: String,
  dot: Color,
  provenance: Provenance?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  marker: Int? = null,
) {
  val parts = DiaryLineParts.of(text)
  Column(modifier.fillMaxWidth()) {
    parts.travel?.let {
      Text(it, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.padding(start = 68.dp, bottom = 4.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(time, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.width(42.dp).padding(top = 13.dp))
      // [marker] = the stop's number on the timeline mini map; the slot is always 18dp so rows line up either way
      Box(Modifier.padding(top = 12.dp).size(18.dp), contentAlignment = Alignment.Center) {
        if (marker == null) {
          Box(Modifier.size(10.dp).background(dot, CircleShape))
        } else {
          Box(Modifier.size(18.dp).background(dot, CircleShape), contentAlignment = Alignment.Center) {
            Text("$marker", style = MaterialTheme.typography.labelSmall, color = HakuColors.Surface)
          }
        }
      }
      SurfaceCard(
        Modifier.weight(1f).clip(HakuRadius.Card).clickable(role = Role.Button, onClickLabel = "แก้ไขหรือลบ", onClick = onClick),
        padding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(parts.title, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink, modifier = Modifier.weight(1f))
          provenance?.let { ProvenanceBadge(it, Modifier.padding(start = 8.dp)) }
        }
        parts.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted) }
      }
    }
  }
}

/**
 * Splits a rule-based diary line ("ถึงร้านกาแฟ ใช้เวลาเดินทางราว 15 นาที อยู่ราว 15 นาที") into title / trip / stay for
 * display only; anything else (an edited or typed line) stays one title.
 */
// ponytail: reads DiaryTemplates' wording back; when lines carry structured fields, pass those instead.
internal data class DiaryLineParts(val title: String, val travel: String?, val detail: String?) {
  companion object {
    private const val TRAVEL = " ใช้เวลาเดินทางราว "
    private const val STAY = " อยู่ราว "

    fun of(text: String): DiaryLineParts {
      val stayAt = text.indexOf(STAY)
      val travelAt = text.indexOf(TRAVEL)
      val titleEnd = listOf(stayAt, travelAt).filter { it > 0 }.minOrNull() ?: return DiaryLineParts(text, null, null)
      val travel = if (travelAt > 0) "เดินทางราว " + text.substring(travelAt + TRAVEL.length, if (stayAt > travelAt) stayAt else text.length) else null
      val stay = if (stayAt > 0) "อยู่ราว " + text.substring(stayAt + STAY.length) else null
      return DiaryLineParts(text.substring(0, titleEnd), travel, stay)
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
