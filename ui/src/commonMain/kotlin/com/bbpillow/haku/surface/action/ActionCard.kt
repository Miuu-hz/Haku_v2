package com.bbpillow.haku.surface.action

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionStatus
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.action.PermissionLevel
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

// ponytail: monoline calendar glyph matching HakuIcons style (24x24, 1.9f stroke, round caps)
private val ActionCalendarIcon: ImageVector by lazy {
  val b = ImageVector.Builder("ActionCalendar", 24.dp, 24.dp, 24f, 24f)
  listOf(
    "M6.5 5.5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2v-10a2 2 0 0 1 2-2z",
    "M4.5 10.5h15",
    "M8 3.5v3",
    "M16 3.5v3",
    "M10 14.5h4",
  ).forEach {
    b.addPath(
      pathData = addPathNodes(it),
      stroke = SolidColor(Color.Black),
      strokeLineWidth = 1.9f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
    )
  }
  b.build()
}

// ponytail: monoline check glyph matching HakuIcons style
private val ActionCheckIcon: ImageVector by lazy {
  val b = ImageVector.Builder("ActionCheck", 24.dp, 24.dp, 24f, 24f)
  b.addPath(
    pathData = addPathNodes("M5 12.5l4.5 4.5L19 7"),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2.2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
  )
  b.build()
}

/**
 * Generalized P4.1 action proposal card for [PermissionLevel.CONFIRM] and [PermissionLevel.BIOMETRIC] actions.
 * Nothing runs until the user taps confirm ([onConfirm]). State-hoisted.
 */
@Composable
fun ActionCard(
  proposal: ActionProposalRecord,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val confirmLabel = when {
    proposal.request.action == CalendarAddArgs.ACTION -> "เพิ่มลงปฏิทิน"
    proposal.level == PermissionLevel.BIOMETRIC -> "ยืนยันด้วยลายนิ้วมือ"
    else -> "ยืนยัน"
  }

  SurfaceCard(
    modifier = modifier.widthIn(max = 340.dp).fillMaxWidth().animateContentSize(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      IconTile(
        icon = ActionCalendarIcon,
        tone = HakuTone.Blue,
        modifier = Modifier.semantics { contentDescription = "คำสั่งที่รอยืนยัน" },
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = proposal.request.title,
          style = MaterialTheme.typography.titleMedium,
          color = HakuColors.Ink,
        )
        if (proposal.request.detail.isNotBlank()) {
          Spacer(Modifier.height(4.dp))
          Text(
            text = proposal.request.detail,
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Muted,
          )
        }
        proposal.request.warning?.let { warning ->
          Spacer(Modifier.height(8.dp))
          Text(
            text = warning,
            style = MaterialTheme.typography.labelSmall,
            color = HakuColors.Coral,
            modifier = Modifier
              .background(HakuColors.Coral.copy(alpha = 0.12f), HakuRadius.Pill)
              .border(1.dp, HakuColors.Coral.copy(alpha = 0.35f), HakuRadius.Pill)
              .padding(horizontal = 12.dp, vertical = 4.dp),
          )
        }
      }
    }

    AnimatedContent(
      targetState = proposal.status,
      label = "ActionStatus",
      modifier = Modifier.fillMaxWidth(),
    ) { status ->
      when (status) {
        ActionStatus.PENDING -> {
          Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CapsuleButton(text = confirmLabel, onClick = onConfirm)
            CapsuleButton(text = "ไม่ใช่", onClick = onDismiss, outline = true)
          }
        }
        ActionStatus.RUNNING -> {
          Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .clip(HakuRadius.Pill)
                .background(HakuColors.Ink.copy(alpha = 0.5f))
                .padding(horizontal = 24.dp, vertical = 13.dp),
              contentAlignment = Alignment.Center,
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(14.dp),
                  color = HakuColors.Surface,
                  strokeWidth = 2.dp,
                )
                Text(
                  text = confirmLabel,
                  style = MaterialTheme.typography.labelLarge,
                  color = HakuColors.Surface,
                )
              }
            }
            Box(
              modifier = Modifier
                .clip(HakuRadius.Pill)
                .background(HakuColors.Bg)
                .border(1.dp, HakuColors.Line, HakuRadius.Pill)
                .padding(horizontal = 24.dp, vertical = 13.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "ไม่ใช่",
                style = MaterialTheme.typography.labelLarge,
                color = HakuColors.Muted.copy(alpha = 0.4f),
              )
            }
          }
        }
        ActionStatus.DONE -> {
          Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = ActionCheckIcon,
              contentDescription = "สำเร็จ",
              tint = HakuColors.Green,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = proposal.message ?: "เพิ่มลงปฏิทินแล้ว · เตือนก่อน 15 นาที",
              style = MaterialTheme.typography.bodySmall,
              color = HakuColors.Ink,
            )
          }
        }
        ActionStatus.DISMISSED -> {
          Text(
            text = proposal.message ?: "ไม่ได้เพิ่ม",
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Muted,
            modifier = Modifier.padding(top = 10.dp),
          )
        }
        ActionStatus.NEEDS_PERMISSION -> {
          Column(
            modifier = Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = proposal.message ?: "ต้องอนุญาตให้ Haku เขียนปฏิทินก่อน",
              style = MaterialTheme.typography.bodySmall,
              color = HakuColors.Muted,
            )
            CapsuleButton(text = "อนุญาต", onClick = onConfirm)
          }
        }
        ActionStatus.FAILED -> {
          Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = proposal.message ?: "เพิ่มไม่สำเร็จ",
              style = MaterialTheme.typography.bodySmall,
              color = HakuColors.Coral,
            )
            CapsuleButton(text = "ลองอีกครั้ง", onClick = onConfirm, outline = true)
          }
        }
        ActionStatus.BLOCKED -> {
          Text(
            text = proposal.message ?: "ระงับคำสั่งนี้ไว้",
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Coral,
            modifier = Modifier.padding(top = 10.dp),
          )
        }
      }
    }
  }
}
