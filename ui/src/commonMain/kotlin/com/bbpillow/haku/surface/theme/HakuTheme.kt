package com.bbpillow.haku.surface.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bbpillow.haku.ui.res.Res
import com.bbpillow.haku.ui.res.anuphan_variable
import com.bbpillow.haku.ui.res.outfit_variable
import org.jetbrains.compose.resources.Font

/** Design tokens from Haku-v2-Plan/haku-mockup.html (:root). Light One UI direction, D23. */
object HakuColors {
  val Bg = Color(0xFFF6F6F9)
  val Surface = Color(0xFFFFFFFF)
  val Ink = Color(0xFF0E0E12)
  val Muted = Color(0xFF6B6B78)
  val Line = Color(0xFFE7E7EF)
  val Blue = Color(0xFF2F8BFF)
  val Violet = Color(0xFF7B61FF)
  val Green = Color(0xFF4ED39A)
  val Butter = Color(0xFFFDECC8)
  val Sky = Color(0xFFD3E0FF)
  val Lilac = Color(0xFFE4D8FF)
  val Peach = Color(0xFFFFE1CC)
  val Mint = Color(0xFFD5F5E6)
  val Coral = Color(0xFFFF7A6B)
  val Track = Color(0xFFEEEFF5) // donut track
  val Shadow = Color(0xFF1E1E46) // rgba(30,30,70) used by every card shadow

  /** --grad: blue → violet, left to right. */
  val Gradient = Brush.horizontalGradient(listOf(Blue, Violet))
}

/** 3D squircle icon tones (.t-amber … .t-coral): light top, deep bottom, shadow in the deep tone. */
enum class HakuTone(val light: Color, val deep: Color) {
  Amber(Color(0xFFFFE3A3), Color(0xFFF2B544)),
  Blue(Color(0xFFA9C8FF), Color(0xFF4F8DFF)),
  Violet(Color(0xFFD0C0FF), Color(0xFF8467FF)),
  Mint(Color(0xFFA6EFCB), Color(0xFF2FBF84)),
  Coral(Color(0xFFFFC6B8), Color(0xFFFF7A6B)),
}

object HakuRadius {
  val Pill = RoundedCornerShape(percent = 50)
  val Card = RoundedCornerShape(22.dp)
  val Small = RoundedCornerShape(14.dp)
}

private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

// ponytail: Compose has no per-glyph fallback between two families, so Anuphan (Thai + Latin)
// is the body font and Outfit (Latin only) is kept for display text and numbers.
@Composable
private fun anuphan() = FontFamily(weights.map { Font(Res.font.anuphan_variable, it) })

@Composable
private fun outfit() = FontFamily(weights.map { Font(Res.font.outfit_variable, it) })

@Composable
fun HakuTheme(content: @Composable () -> Unit) {
  val body = anuphan()
  val display = outfit()
  MaterialTheme(
    colorScheme = lightColorScheme(
      primary = HakuColors.Ink, // black capsule buttons
      onPrimary = HakuColors.Surface,
      secondary = HakuColors.Blue,
      tertiary = HakuColors.Violet,
      background = HakuColors.Bg,
      onBackground = HakuColors.Ink,
      surface = HakuColors.Surface,
      onSurface = HakuColors.Ink,
      onSurfaceVariant = HakuColors.Muted,
      outline = HakuColors.Line,
      error = HakuColors.Coral,
    ),
    shapes = Shapes(small = HakuRadius.Small, medium = HakuRadius.Card, large = HakuRadius.Card),
    typography = Typography(
      displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 40.sp),
      headlineMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
      titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp), // .big numbers
      titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
      titleSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp), // card h5
      bodyLarge = TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
      bodyMedium = TextStyle(fontFamily = body, fontSize = 14.sp, lineHeight = 21.sp),
      bodySmall = TextStyle(fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp), // .lbl
      labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 14.sp),
      labelMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.7.sp), // .sec-lbl
      labelSmall = TextStyle(fontFamily = body, fontSize = 12.sp, lineHeight = 16.sp), // chips, badges
    ),
    content = content,
  )
}
