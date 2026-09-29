package com.bbpillow.haku.surface.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * .ico: One UI squircle tile with a two-tone gradient, top gloss, bottom inner shade and a shadow in the same tone.
 */
// ponytail: faked 3D with gradients + a vector glyph; swap for the rendered 3D icon assets (04-asset.md N) when they exist.
@Composable
fun IconTile(icon: ImageVector, tone: HakuTone, modifier: Modifier = Modifier, size: Dp = 46.dp) {
  val shape = RoundedCornerShape(size * 0.34f) // 11px on a 32px tile
  Box(
    modifier
      .size(size)
      .hakuShadow(shape, tone.deep, elevation = 8.dp)
      .drawBehind {
        val r = CornerRadius(size.toPx() * 0.34f)
        drawRoundRect(Brush.linearGradient(listOf(tone.light, tone.deep), Offset(this.size.width * 0.25f, 0f), Offset(this.size.width * 0.75f, this.size.height)), cornerRadius = r)
        // inner shade at the bottom
        drawRoundRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f)), cornerRadius = r)
        // glossy highlight on the top half
        val w = this.size.width
        val h = this.size.height
        drawOval(
          Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), startY = h * 0.07f, endY = h * 0.45f),
          topLeft = Offset(w * 0.18f, h * 0.07f),
          size = Size(w * 0.64f, h * 0.38f),
        )
      },
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = null, tint = HakuColors.Surface, modifier = Modifier.size(size * 0.5f))
  }
}
