package com.bbpillow.haku.surface.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/**
 * The day's stops drawn on a plain card (mockup .map): no tiles, no map SDK, nothing leaves the device (PDPA). Positions
 * come pre-fitted from [TimelineMap]; this only draws them at [TimelineMap.ASPECT]. Dot colour and number match the rows.
 */
@Composable
fun TimelineMiniMap(map: TimelineMapState, modifier: Modifier = Modifier) {
  val measurer = rememberTextMeasurer()
  val numberStyle = MaterialTheme.typography.labelSmall.copy(color = HakuColors.Surface)
  val home = rememberVectorPainter(HakuIcons.Home)
  val work = rememberVectorPainter(HakuIcons.Work)
  val description = "แผนที่ย่อ " + map.points.joinToString(" · ") { "${it.number} ${it.label}" }
  Canvas(
    modifier.fillMaxWidth().aspectRatio(TimelineMap.ASPECT.toFloat()).clip(HakuRadius.Card).background(HakuColors.Track)
      .semantics { contentDescription = description },
  ) {
    val at = map.points.map { Offset(it.x.toFloat() * size.width, it.y.toFloat() * size.height) }
    val route = Path().apply { at.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
    drawPath(route, HakuColors.Gradient, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

    val r = 9.dp.toPx()
    map.points.forEachIndexed { i, p ->
      drawCircle(Color.White, r + 2.dp.toPx(), at[i])
      drawCircle(mapDot(p.number), r, at[i])
      val text = measurer.measure("${p.number}", numberStyle)
      drawText(text, topLeft = at[i] - Offset(text.size.width / 2f, text.size.height / 2f))
      val icon = when (p.category) {
        PlaceCategory.HOME -> home
        PlaceCategory.WORK -> work
        PlaceCategory.OTHER -> null
      } ?: return@forEachIndexed
      // a small white badge above-right of the dot, so the number stays readable
      val s = 14.dp.toPx()
      val badge = at[i] + Offset(r * 0.7f, -r * 0.7f - s)
      drawCircle(Color.White, s * 0.62f, badge + Offset(s / 2, s / 2))
      translate(badge.x, badge.y) { with(icon) { draw(Size(s, s), colorFilter = ColorFilter.tint(HakuColors.Ink)) } }
    }
  }
}

/** Same palette as the timeline row dots; a row with a map number takes its stop's colour. */
internal fun mapDot(number: Int): Color = TimelineDots[(number - 1).mod(TimelineDots.size)]
