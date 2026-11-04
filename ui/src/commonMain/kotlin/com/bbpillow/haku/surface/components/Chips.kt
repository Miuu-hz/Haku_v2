package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** A borderless pill in a set tone, as the Home design draws them. */
@Composable
fun ToneChip(text: String, background: Color, textColor: Color, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = textColor,
    modifier = modifier
      .background(background, HakuRadius.Pill)
      .padding(horizontal = 12.dp, vertical = 5.dp),
  )
}

/** .chip: small grey pill (counts, dates, status); dark = .chip.dark. */
@Composable
fun StatChip(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = if (dark) HakuColors.Surface else HakuColors.Muted,
    modifier = modifier
      .background(if (dark) HakuColors.Ink else HakuColors.Bg, HakuRadius.Pill)
      .border(1.dp, if (dark) HakuColors.Ink else HakuColors.Line, HakuRadius.Pill)
      .padding(horizontal = 12.dp, vertical = 4.dp),
  )
}

/** Where a memory line came from; external data never becomes permanent memory. */
enum class Provenance(val label: String, val color: Color) {
  User("คุณพิมพ์เอง", HakuColors.Sky),
  System("ระบบสรุป", HakuColors.Lilac),
  External("ภายนอก", HakuColors.Butter),
}

/** .badge: pastel provenance pill; External carries a tiny lock (cannot become permanent). */
@Composable
fun ProvenanceBadge(provenance: Provenance, modifier: Modifier = Modifier) {
  Row(
    modifier.background(provenance.color, HakuRadius.Pill).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (provenance == Provenance.External) {
      Icon(HakuIcons.Lock, contentDescription = null, tint = HakuColors.Ink, modifier = Modifier.size(11.dp))
    }
    Text(provenance.label, style = MaterialTheme.typography.labelSmall, color = HakuColors.Ink)
  }
}

/** Black band "data that left the device today: N times" from the Memory screen. */
@Composable
fun EgressCounter(count: Int, modifier: Modifier = Modifier) {
  SurfaceCard(modifier.fillMaxWidth(), color = HakuColors.Ink) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "ข้อมูลออกนอกเครื่องวันนี้",
        style = MaterialTheme.typography.bodyMedium,
        color = HakuColors.Surface.copy(alpha = 0.75f),
        modifier = Modifier.weight(1f),
      )
      Text("$count", style = MaterialTheme.typography.titleLarge, color = HakuColors.Surface)
      Text(" ครั้ง", style = MaterialTheme.typography.titleMedium, color = HakuColors.Surface)
    }
  }
}
