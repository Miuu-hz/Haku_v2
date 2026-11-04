package com.bbpillow.haku.surface.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.tasks.taskQuadrantColor
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone
import com.bbpillow.haku.task.TaskCounts
import com.bbpillow.haku.task.TaskQuadrant
import com.bbpillow.haku.task.TaskRules

@Composable
fun HomeBriefScreen(
  state: HomeBriefUiState,
  isCapturing: Boolean,
  onOpenDay: (String) -> Unit,
  onReadLearning: (LearningCard) -> Unit,
  onDismissLearning: (LearningCard) -> Unit,
  modifier: Modifier = Modifier,
  /** The header's Haku Plus chip (P9.4). */
  onOpenPlus: () -> Unit = {},
  /** The stats row opens the stats screen (P2.8: week/month/year + comparison). */
  onOpenStats: () -> Unit = {},
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
  /** e.g. "Gemma E2B พร้อม · ย่อยความจำตอนชาร์จ"; null = line hidden. */
  modelLine: String? = null,
  onOpenTasks: () -> Unit = {},
  onConfirmDecision: (String) -> Unit = {},
  onDismissDecision: (String) -> Unit = {},
  /** Result of the last tap on a decision, e.g. a calendar permission problem; null = nothing to say. */
  decisionMessage: String? = null,
) {
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    if (state.isDayOne) {
      item { DayOne(state, isCapturing) }
      return@LazyColumn
    }
    item { HomeHeader(state.greeting, state.headline, onOpenPlus) }
    item { StoryCard(state, onOpenDay) }
    if (state.decisions.isNotEmpty()) {
      item { DecisionsCard(state.decisions, decisionMessage, onConfirmDecision, onDismissDecision) }
    }
    item { HomeTasksCard(state.taskCounts, onOpenTasks) }
    state.appointments?.let { today -> item { AppointmentsCard(today) } }
    val stats = state.stats
    if (stats != null) {
      item { StatsRow(stats, onOpenStats) }
    } else if (state.weekly != null) {
      item {
        StatChip(
          text = "ดูสถิติ",
          modifier = Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onOpenStats),
        )
      }
    }
    state.learning?.let { card -> item { LearningBoard(card, onReadLearning, onDismissLearning) } }
    modelLine?.let { line -> item { ModelLineStatus(line) } }
    item { CaptureStatus(isCapturing) }
  }
}

@Composable
private fun HomeHeader(greeting: String, headline: String, onOpenPlus: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(greeting, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      Text(headline, style = MaterialTheme.typography.headlineMedium, color = HakuColors.Ink)
    }
    StatChip(
      text = "Haku Plus",
      modifier = Modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onOpenPlus),
    )
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

@Composable
private fun DecisionsCard(
  decisions: List<HomeDecisionRow>,
  decisionMessage: String?,
  onConfirm: (String) -> Unit,
  onDismiss: (String) -> Unit,
) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("รอคุณตัดสิน", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.weight(1f))
      StatChip("${decisions.size}")
    }
    Spacer(Modifier.height(8.dp))
    decisions.forEach { row ->
      DecisionRowItem(row, onConfirm = { onConfirm(row.id) }, onDismiss = { onDismiss(row.id) })
    }
    decisionMessage?.let { msg ->
      Text(
        text = msg,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Muted,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
private fun DecisionRowItem(
  row: HomeDecisionRow,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
    val stackButtons = maxWidth < 400.dp
    if (stackButtons) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          IconTile(HakuIcons.Calendar, HakuTone.Blue, size = 36.dp)
          DecisionText(row, Modifier.weight(1f))
        }
        Row(
          modifier = Modifier.padding(start = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CapsuleButton("เพิ่ม", onConfirm)
          CapsuleButton("ไม่ใช่", onDismiss, outline = true)
        }
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        IconTile(HakuIcons.Calendar, HakuTone.Blue, size = 36.dp)
        DecisionText(row, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          CapsuleButton("เพิ่ม", onConfirm)
          CapsuleButton("ไม่ใช่", onDismiss, outline = true)
        }
      }
    }
  }
}

@Composable
private fun DecisionText(row: HomeDecisionRow, modifier: Modifier) {
  Column(modifier) {
    Text(row.heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(row.detail, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    row.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = HakuColors.Coral) }
  }
}

@Composable
private fun HomeTasksCard(taskCounts: TaskCounts, onOpenTasks: () -> Unit) {
  SurfaceCard(
    modifier = Modifier
      .fillMaxWidth()
      .clip(HakuRadius.Card)
      .clickable(role = Role.Button, onClick = onOpenTasks),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("งานวันนี้", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.weight(1f))
      Icon(HakuIcons.Chevron, contentDescription = null, tint = HakuColors.Muted, modifier = Modifier.size(16.dp))
    }
    Spacer(Modifier.height(8.dp))
    if (taskCounts.total == 0) {
      Text("ยังไม่มีงาน แตะเพื่อเพิ่ม", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        val boxes = listOf(
          TaskQuadrant.IMPORTANT_URGENT to taskCounts.importantUrgent,
          TaskQuadrant.IMPORTANT to taskCounts.important,
          TaskQuadrant.URGENT to taskCounts.urgent,
          TaskQuadrant.SOMEDAY to taskCounts.someday,
        )
        boxes.forEach { (quadrant, count) ->
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "$count",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = taskQuadrantColor(quadrant),
            )
            Text(
              text = TaskRules.label(quadrant),
              style = MaterialTheme.typography.labelSmall,
              color = HakuColors.Muted,
            )
          }
        }
      }
    }
  }
}

/** Today's calendar: past rows muted, the next one in violet with a chip, the rest as "อีก N นัด". */
@Composable
private fun AppointmentsCard(today: TodayAppointments) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Text("วันนี้มีนัด", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    today.rows.forEach { row ->
      val color = when {
        row.isNext -> HakuColors.Violet
        row.isPast -> HakuColors.Muted
        else -> HakuColors.Ink
      }
      Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(row.timeText, style = MaterialTheme.typography.bodySmall, color = color)
        Text(row.title, style = if (row.isNext) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        if (row.isNext) StatChip("ถัดไป")
      }
    }
    if (today.moreCount > 0) StatChip("อีก ${today.moreCount} นัด", Modifier.padding(top = 8.dp))
  }
}

/** Number tiles for measured stats. Whole row is tappable to open stats. */
@Composable
private fun StatsRow(stats: HomeStats, onOpenStats: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(HakuRadius.Card)
      .clickable(role = Role.Button, onClick = onOpenStats),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    stats.outOfHomeMinutes?.let { minutes ->
      StatNumberTile(
        value = durationText(minutes),
        label = "เวลานอกบ้าน",
        background = HakuColors.Surface,
        modifier = Modifier.weight(1f),
      )
    }
    StatNumberTile(
      value = "${stats.placeCount}",
      label = "สถานที่",
      background = HakuColors.Sky,
      modifier = Modifier.weight(1f),
    )
    stats.steps?.let { steps ->
      StatNumberTile(
        value = formatThousands(steps),
        label = "ก้าว",
        background = HakuColors.Butter,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun StatNumberTile(
  value: String,
  label: String,
  background: Color,
  modifier: Modifier = Modifier,
) {
  SurfaceCard(
    modifier = modifier,
    color = background,
    padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
  ) {
    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = HakuColors.Ink)
    Spacer(Modifier.height(4.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted)
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

@Composable
private fun ModelLineStatus(line: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(HakuIcons.Box, contentDescription = null, tint = HakuColors.Green, modifier = Modifier.size(16.dp))
    Text(line, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
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
