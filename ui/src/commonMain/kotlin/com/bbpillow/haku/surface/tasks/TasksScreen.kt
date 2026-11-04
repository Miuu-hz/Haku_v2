package com.bbpillow.haku.surface.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.task.TaskQuadrant

private val QuadrantColors = mapOf(
  TaskQuadrant.IMPORTANT_URGENT to Color(0xFFE5484D),
  TaskQuadrant.IMPORTANT to Color(0xFF4A78F0),
  TaskQuadrant.URGENT to Color(0xFFC77F12),
  TaskQuadrant.SOMEDAY to Color(0xFF6B6B78),
)

internal fun taskQuadrantColor(quadrant: TaskQuadrant): Color =
  QuadrantColors[quadrant] ?: HakuColors.Muted

@Composable
fun TasksScreen(
  state: TasksUiState,
  onToggleDone: (String) -> Unit,
  onOpenTask: (String) -> Unit,
  onAdd: () -> Unit,
  onBack: () -> Unit,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  val q1 = state.quadrants.getOrNull(0)
  val q2 = state.quadrants.getOrNull(1)
  val q3 = state.quadrants.getOrNull(2)
  val q4 = state.quadrants.getOrNull(3)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TasksHeader(onBack = onBack, onAdd = onAdd)

    if (q1 != null && q2 != null) {
      Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        QuadrantCard(q1, onToggleDone, onOpenTask, Modifier.weight(1f).fillMaxHeight())
        QuadrantCard(q2, onToggleDone, onOpenTask, Modifier.weight(1f).fillMaxHeight())
      }
    }

    if (q3 != null && q4 != null) {
      Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        QuadrantCard(q3, onToggleDone, onOpenTask, Modifier.weight(1f).fillMaxHeight())
        QuadrantCard(q4, onToggleDone, onOpenTask, Modifier.weight(1f).fillMaxHeight())
      }
    }

    Text(
      text = "เร่งด่วน = กำหนดส่งใน 48 ชม. หรือเลยแล้ว · ความสำคัญแตะเปลี่ยนได้ในงาน",
      style = MaterialTheme.typography.bodySmall,
      color = HakuColors.Muted,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun TasksHeader(onBack: () -> Unit, onAdd: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }
    Text(
      text = "งาน",
      style = MaterialTheme.typography.headlineMedium,
      color = HakuColors.Ink,
      modifier = Modifier.weight(1f),
    )
    CapsuleButton("เพิ่มงาน", onAdd)
  }
}

@Composable
private fun QuadrantCard(
  quadrantUi: TaskQuadrantUi,
  onToggleDone: (String) -> Unit,
  onOpenTask: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val boxColor = taskQuadrantColor(quadrantUi.quadrant)
  SurfaceCard(
    modifier = modifier.defaultMinSize(minHeight = 180.dp),
    padding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
  ) {
    Text(
      text = quadrantUi.label,
      style = MaterialTheme.typography.titleSmall,
      color = boxColor,
    )
    Spacer(Modifier.height(4.dp))
    if (quadrantUi.rows.isEmpty()) {
      Text("ว่าง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    } else {
      quadrantUi.rows.forEach { row ->
        TaskRowItem(row = row, boxColor = boxColor, onToggleDone = onToggleDone, onOpenTask = onOpenTask)
      }
    }
  }
}

@Composable
private fun TaskRowItem(
  row: TaskRowUi,
  boxColor: Color,
  onToggleDone: (String) -> Unit,
  onOpenTask: (String) -> Unit,
) {
  val desc = if (row.isDone) "ยกเลิกเสร็จ ${row.title}" else "ทำเสร็จ ${row.title}"
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = { onToggleDone(row.id) })
        .semantics { contentDescription = desc },
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier = Modifier
          .size(24.dp)
          .background(if (row.isDone) boxColor else Color.Transparent, CircleShape)
          .border(2.dp, boxColor, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (row.isDone) {
          Icon(HakuIcons.Check, contentDescription = null, tint = HakuColors.Surface, modifier = Modifier.size(14.dp))
        }
      }
    }

    Column(
      modifier = Modifier
        .weight(1f)
        .clip(HakuRadius.Small)
        .clickable(role = Role.Button, onClick = { onOpenTask(row.id) })
        .padding(vertical = 4.dp),
    ) {
      Text(
        text = row.title,
        style = MaterialTheme.typography.bodyMedium.copy(
          textDecoration = if (row.isDone) TextDecoration.LineThrough else TextDecoration.None,
        ),
        color = if (row.isDone) HakuColors.Muted else HakuColors.Ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      row.dueText?.let { due ->
        Text(
          text = if (row.isOverdue) "เลย $due" else due,
          style = MaterialTheme.typography.labelSmall,
          color = if (row.isOverdue) HakuColors.Coral else boxColor,
        )
      }
    }
  }
}
