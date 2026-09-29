package com.bbpillow.haku.surface.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors

/** Thick donut with rounded ends, blue → violet → green gradient over a light track. fraction is 0..1. */
@Composable
fun DonutChart(fraction: Float, modifier: Modifier = Modifier, size: Dp = 112.dp, label: String = "${(fraction * 100).toInt()}%") {
  Box(modifier.size(size), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val w = this.size.width * 0.125f // stroke 10 on an 80 viewBox
      val inset = this.size.width * 0.125f // r = 30 on 80
      val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
      val topLeft = Offset(inset, inset)
      drawArc(HakuColors.Track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
      drawArc(
        Brush.linearGradient(
          0f to HakuColors.Blue, 0.55f to HakuColors.Violet, 1f to HakuColors.Green,
          start = Offset.Zero, end = Offset(this.size.width, this.size.height),
        ),
        -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize,
        style = Stroke(w, cap = StrokeCap.Round),
      )
    }
    Text(label, style = MaterialTheme.typography.titleMedium, color = HakuColors.Ink)
  }
}
