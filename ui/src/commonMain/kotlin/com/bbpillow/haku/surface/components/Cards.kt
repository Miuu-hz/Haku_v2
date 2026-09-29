package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

private val CardPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)

/** Soft raised shadow shared by cards and boards (CSS 0 12px 24px -16px rgba(30,30,70,.35)). */
fun Modifier.hakuShadow(shape: Shape = HakuRadius.Card, color: Color = HakuColors.Shadow, elevation: Dp = 10.dp) =
  shadow(elevation, shape, clip = false, ambientColor = color.copy(alpha = 0.08f), spotColor = color.copy(alpha = 0.35f))

/** .card: white rounded surface. */
@Composable
fun SurfaceCard(
  modifier: Modifier = Modifier,
  color: Color = HakuColors.Surface,
  padding: PaddingValues = CardPadding,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier.hakuShadow().background(color, HakuRadius.Card).padding(padding),
    content = content,
  )
}

/** .hl: the one highlighted card per screen, 2dp blue→violet stroke and a violet-tinted shadow. */
@Composable
fun HighlightCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier
      .hakuShadow(color = HakuColors.Violet, elevation = 12.dp)
      .background(HakuColors.Surface, HakuRadius.Card)
      .border(2.dp, HakuColors.Gradient, HakuRadius.Card)
      .padding(CardPadding),
    content = content,
  )
}

/** .pastel: category board, pass HakuColors.Butter / Sky / Lilac / Peach / Mint. Soft top light over the fill. */
@Composable
fun PastelBoard(color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier
      .hakuShadow()
      .clip(HakuRadius.Card)
      .background(color)
      .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.6f), 0.55f to Color.Transparent))
      .padding(16.dp),
    content = content,
  )
}

/** .screen background: off-white with a lilac glow top-right and a peach glow bottom-left. */
@Composable
fun ScreenBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  Box(
    modifier
      .background(HakuColors.Bg)
      .drawBehind {
        val r = size.width * 0.75f
        drawCircle(
          Brush.radialGradient(listOf(HakuColors.Lilac, HakuColors.Lilac.copy(alpha = 0f)), Offset(size.width, 0f), r),
          r, Offset(size.width, 0f),
        )
        val p = Offset(0f, size.height * 0.8f)
        drawCircle(
          Brush.radialGradient(listOf(HakuColors.Peach.copy(alpha = 0.8f), HakuColors.Peach.copy(alpha = 0f)), p, r * 0.8f),
          r * 0.8f, p,
        )
      },
    content = content,
  )
}
