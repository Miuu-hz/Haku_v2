package com.bbpillow.haku.surface.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Monoline glyphs copied from the mockup's <symbol> sprite (24x24, round caps).
 * Stroke is black; tint them with Icon(tint = ...).
 */
object HakuIcons {
  val Home = icon("Home", "M4 11.5 12 5l8 6.5V19a1 1 0 0 1-1 1h-4v-5h-6v5H5a1 1 0 0 1-1-1z")
  val Route = icon("Route", circle(6f, 18f, 2f), circle(18f, 6f, 2f), "M8 18h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7")
  val Chat = icon("Chat", "M5 17.5V7a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H9l-4 3z")
  val Grid = icon(
    "Grid",
    rect(4.5f, 4.5f, 6f, 6f, 1.5f), rect(13.5f, 4.5f, 6f, 6f, 1.5f),
    rect(4.5f, 13.5f, 6f, 6f, 1.5f), rect(13.5f, 13.5f, 6f, 6f, 1.5f),
  )
  val Walk = icon("Walk", circle(13f, 4.5f, 1.5f), "m9 21 2.5-6 2.5 2v4M8 11l3-3 3 1.5 2 3M11.5 15l1-6")
  val Book = icon("Book", "M5 5.5A1.5 1.5 0 0 1 6.5 4H18v14H6.5A1.5 1.5 0 0 0 5 19.5z", "M5 19.5A1.5 1.5 0 0 0 6.5 21H18v-3")
  val Lock = icon("Lock", rect(5f, 10.5f, 14f, 9.5f, 2.5f), "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5", "M12 14.5v2")
  val Temp = icon("Temp", "M10 14.5V5a2 2 0 0 1 4 0v9.5a3.5 3.5 0 1 1-4 0z")
  val Box = icon("Box", "M12 3 20 7.5v9L12 21l-8-4.5v-9z", "M4 7.5 12 12l8-4.5M12 12v9")
  val File = icon("File", "M7 3.5h7l4 4V19a1.5 1.5 0 0 1-1.5 1.5h-9.5A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z", "M14 3.5V8h4")
  val Send = icon("Send", "M5 12h13M13 6l6 6-6 6")
  val Img = icon("Img", rect(4f, 5f, 16f, 14f, 2.5f), circle(9f, 10f, 1.5f), "m5 17 4.5-4 3.5 3 2.5-2L20 17")
  val Spark = icon("Spark", "M12 4v4M12 16v4M4 12h4M16 12h4M7 7l2 2M15 15l2 2M17 7l-2 2M9 15l-2 2")
  val Chart = icon("Chart", rect(4.5f, 4.5f, 15f, 15f, 3f), "M9 15.5v-3M12 15.5v-7M15 15.5v-5")

  /** Overflow (three dots), row chevron, back and settings gear: drawn in the same monoline style. */
  val More = icon("More", circle(6f, 12f, 0.6f), circle(12f, 12f, 0.6f), circle(18f, 12f, 0.6f))
  val Chevron = icon("Chevron", "M10 7l5 5-5 5")
  val Back = icon("Back", "M14 7l-5 5 5 5")
  val Settings = icon(
    "Settings",
    circle(12f, 12f, 2.8f),
    circle(12f, 12f, 6.6f),
    "M12 3v2.2M12 18.8V21M3 12h2.2M18.8 12H21M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6",
  )
}

private fun icon(name: String, vararg paths: String): ImageVector {
  val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
  paths.forEach {
    b.addPath(
      pathData = addPathNodes(it),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 1.9f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }
  return b.build()
}

private fun circle(cx: Float, cy: Float, r: Float) =
  "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
  val iw = w - 2 * rx
  val ih = h - 2 * rx
  return "M${x + rx} ${y}h${iw}a$rx $rx 0 0 1 $rx ${rx}v${ih}a$rx $rx 0 0 1 ${-rx} ${rx}" +
    "h${-iw}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-ih}a$rx $rx 0 0 1 $rx ${-rx}z"
}
