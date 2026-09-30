package com.bbpillow.haku.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.DonutChart
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * Screen 1 of haku-mockup.html (asset A1-A6): greeting, the day's story, measured stats, the learning card slot, and
 * the day-one empty state. State-hoisted: [state] comes from HomeBriefPresenter; tapping the story opens that day's
 * timeline (where every line shows its source). [isCapturing] = location capture is running (system status, A6).
 */
// ponytail: no pinned-skill shortcuts or model/charging-queue status yet; add with P4.x / P5.1.
@Composable
fun HomeBriefScreen(
  state: HomeBriefUiState,
  isCapturing: Boolean,
  onOpenDay: (String) -> Unit,
  onReadLearning: (LearningCard) -> Unit,
  onDismissLearning: (LearningCard) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (state.isDayOne) {
      item { DayOne(state, isCapturing) }
      return@LazyColumn
    }
    item { ScreenTitle(state.headline, eyebrow = state.greeting) }
    item { StoryCard(state, onOpenDay) }
    state.stats?.let { stats -> item { StatsRow(stats) } }
    state.weekly?.let { card -> item { WeeklyBoard(card) } }
    state.learning?.let { card -> item { LearningBoard(card, onReadLearning, onDismissLearning) } }
    item { CaptureStatus(isCapturing) }
  }
}

@Composable
private fun StoryCard(state: HomeBriefUiState, onOpenDay: (String) -> Unit) {
  HighlightCard(Modifier.fillMaxWidth().clip(HakuRadius.Card).clickable(role = Role.Button) { onOpenDay(state.storyDay) }) {
    Text(state.storyTitle, style = MaterialTheme.typography.titleSmall)
    Text(state.storyHeader, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    Spacer(Modifier.height(6.dp))
    val summary = state.storySummary
    if (summary != null) {
      Text(summary, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
      Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatChip("จากบันทึก ${state.storyLineCount} รายการ")
      }
    } else if (state.storyLines.isEmpty()) {
      val whose = if (state.period == BriefPeriod.MORNING) "ของเมื่อวาน" else "วันนี้"
      Text("ยังไม่มีสรุป$whose", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
    } else {
      state.storyLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted) }
      Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatChip("บันทึก ${state.storyLineCount} รายการ")
        if (state.storyLineCount > state.storyLines.size) StatChip("ดูทั้งหมด")
      }
    }
  }
}

/** Only cards backed by a number are drawn: no donut without a home, no steps without a step source. */
@Composable
private fun StatsRow(stats: HomeStats) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    val fraction = stats.outOfHomeFraction
    val minutes = stats.outOfHomeMinutes
    if (fraction != null && minutes != null) {
      SurfaceCard(Modifier.weight(1f).fillMaxHeight()) {
        DonutChart(fraction, Modifier.align(Alignment.CenterHorizontally))
        Text(
          "เวลานอกบ้าน ${durationText(minutes)}",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
          textAlign = TextAlign.Center,
          modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )
      }
    }
    PastelBoard(HakuColors.Sky, Modifier.weight(1f).fillMaxHeight()) {
      IconTile(HakuIcons.Route, HakuTone.Blue)
      Spacer(Modifier.weight(1f).height(10.dp))
      Text("${stats.placeCount}", style = MaterialTheme.typography.titleLarge)
      Text("สถานที่", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    }
    stats.steps?.let { steps ->
      PastelBoard(HakuColors.Butter, Modifier.weight(1f).fillMaxHeight()) {
        IconTile(HakuIcons.Walk, HakuTone.Amber)
        Spacer(Modifier.weight(1f).height(10.dp))
        Text(formatThousands(steps), style = MaterialTheme.typography.titleLarge)
        Text("ก้าว", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      }
    }
  }
}

/** A5: this week's measured lines; FREE sees a quiet note that the week-over-week comparison is Plus. */
@Composable
private fun WeeklyBoard(card: WeeklyCard) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Text("สรุปสัปดาห์นี้", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    card.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted) }
    if (card.comparisonLocked) StatChip("เทียบกับสัปดาห์ก่อนใน Haku Plus", Modifier.padding(top = 10.dp))
  }
}

/** A4: one lilac board, read or "not interested". */
@Composable
private fun LearningBoard(card: LearningCard, onRead: (LearningCard) -> Unit, onDismiss: (LearningCard) -> Unit) {
  PastelBoard(HakuColors.Lilac, Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      IconTile(HakuIcons.Book, HakuTone.Violet)
      Text("เรื่องน่ารู้วันนี้", style = MaterialTheme.typography.titleSmall)
    }
    Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(card.summary, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 4.dp))
    StatChip("จากเรื่องที่คุณสนใจ: ${card.topic}", Modifier.padding(top = 10.dp))
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      CapsuleButton("อ่าน", { onRead(card) })
      CapsuleButton("ไม่สนใจเรื่องนี้", { onDismiss(card) }, outline = true)
    }
    Text("ที่มา: ${card.source}", style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 8.dp))
  }
}

/** A6: day one, nothing recorded yet. */
@Composable
private fun DayOne(state: HomeBriefUiState, isCapturing: Boolean) {
  Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    IconTile(HakuIcons.Box, HakuTone.Violet, size = 112.dp)
    Text(state.greeting, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, modifier = Modifier.padding(top = 32.dp))
    Text(state.headline, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(
      "ใช้ชีวิตตามปกติ แล้วเย็นนี้กลับมาดูเรื่องราวของวันนี้",
      style = MaterialTheme.typography.bodyMedium,
      color = HakuColors.Muted,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
    )
    CaptureStatus(isCapturing)
  }
}

@Composable
private fun CaptureStatus(isCapturing: Boolean) {
  SurfaceCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Box(Modifier.size(8.dp).background(if (isCapturing) HakuColors.Green else HakuColors.Line, CircleShape))
      Text(
        if (isCapturing) "กำลังบันทึกการเดินทาง" else "ยังไม่ได้เปิดการบันทึกการเดินทาง",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

private fun durationText(minutes: Int): String {
  val h = minutes / 60
  val m = minutes % 60
  return when {
    h == 0 -> "$m นาที"
    m == 0 -> "$h ชม."
    else -> "$h ชม. $m นาที"
  }
}

private fun formatThousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
