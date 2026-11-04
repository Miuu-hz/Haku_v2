package com.bbpillow.haku.surface.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
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
fun PlainDayMap(map: TimelineMapState, modifier: Modifier = Modifier) {
  val current = map.points.firstOrNull { it.since != null }
  val measurer = rememberTextMeasurer()
  val numberStyle = MaterialTheme.typography.labelSmall.copy(color = HakuColors.Surface)
  val home = rememberVectorPainter(HakuIcons.Home)
  val work = rememberVectorPainter(HakuIcons.Work)
  val currentDesc = current?.let { " ตอนนี้อยู่ที่ ${it.label} ${it.since}" } ?: ""
  val description = "แผนที่ย่อ " + map.points.joinToString(" · ") { "${it.number} ${it.label}" } + currentDesc
  Canvas(
    modifier
      .fillMaxWidth()
      .aspectRatio(TimelineMap.ASPECT.toFloat())
      .clip(HakuRadius.Card)
      .background(HakuColors.Track)
      .semantics { contentDescription = description },
  ) {
    val at = map.points.map { Offset(it.x.toFloat() * size.width, it.y.toFloat() * size.height) }
    if (at.size > 1) {
      val route = Path().apply { at.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
      drawPath(route, HakuColors.Gradient, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    val r = 9.dp.toPx()
    map.points.forEachIndexed { i, p ->
      if (p.since != null) {
        drawCircle(HakuColors.Green, r + 5.dp.toPx(), at[i], style = Stroke(2.dp.toPx()))
      }
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

@Composable
fun TimelineMiniMap(
  map: TimelineMapState,
  modifier: Modifier = Modifier,
  drawing: @Composable (TimelineMapState, Modifier) -> Unit = { m, mod -> PlainDayMap(m, mod) },
  onOpenPin: ((TimelineMapPoint) -> Unit)? = null,
) {
  val current = map.points.firstOrNull { it.since != null }
  Column(modifier = modifier) {
    drawing(map, Modifier.fillMaxWidth().aspectRatio(TimelineMap.ASPECT.toFloat()).clip(HakuRadius.Card))
    if (current != null) {
      Text(
        "ตอนนี้: ${current.label} · ${current.since}",
        style = MaterialTheme.typography.labelSmall,
        color = HakuColors.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
      )
    }
    if (onOpenPin != null) {
      LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(map.points, key = { it.number }) { p ->
          Row(
            modifier = Modifier
              .heightIn(min = 48.dp)
              .clip(HakuRadius.Pill)
              .border(1.dp, HakuColors.Line, HakuRadius.Pill)
              .background(HakuColors.Surface)
              .clickable(
                role = Role.Button,
                onClickLabel = "เปิดใน Google Maps",
                onClick = { onOpenPin(p) },
              )
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              "${p.number}",
              style = MaterialTheme.typography.labelMedium,
              color = mapDot(p.number),
            )
            Text(
              p.label,
              style = MaterialTheme.typography.labelMedium,
              color = HakuColors.Ink,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.widthIn(max = 160.dp),
            )
          }
        }
      }
      Text(
        "แตะเพื่อเปิดใน Google Maps",
        style = MaterialTheme.typography.labelSmall,
        color = HakuColors.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
      )
    }
  }
}

/** Same palette as the timeline row dots; a row with a map number takes its stop's colour. */
internal fun mapDot(number: Int): Color = TimelineDots[(number - 1).mod(TimelineDots.size)]
