package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** .btn (black pill) or, with outline = true, .copy (light pill with a hairline border). */
@Composable
fun CapsuleButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, outline: Boolean = false) {
  val shape = HakuRadius.Pill
  Box(
    modifier
      .clip(shape)
      .background(if (outline) HakuColors.Bg else HakuColors.Ink)
      .then(if (outline) Modifier.border(1.dp, HakuColors.Line, shape) else Modifier)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 13.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = if (outline) HakuColors.Ink else HakuColors.Surface)
  }
}
