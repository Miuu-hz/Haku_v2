package com.bbpillow.haku.surface.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

@Composable
fun ChatHistoryScreen(
  sessions: List<ChatSessionSummary>,
  /** e.g. "อ. 3 พ.ย." for a time; the caller knows the time zone. */
  dateLabel: (Long) -> String,
  onBack: () -> Unit,
  onOpen: (Long) -> Unit,
  onDelete: (Long) -> Unit,
  onTogglePin: (Long) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier
        .size(44.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }

    ScreenTitle("แชทก่อนหน้า", eyebrow = "เก็บในเครื่อง หายใน 7 วันถ้าไม่ปักหมุด")

    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (sessions.isEmpty()) {
        item {
          Text(
            text = "ยังไม่มีแชทก่อนหน้า",
            style = MaterialTheme.typography.bodyMedium,
            color = HakuColors.Muted,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      } else {
        items(sessions, key = { it.id }) { session ->
          ChatSessionCard(
            session = session,
            dateLabel = dateLabel,
            onOpen = onOpen,
            onDelete = onDelete,
            onTogglePin = onTogglePin,
          )
        }
      }
    }
  }
}

@Composable
private fun ChatSessionCard(
  session: ChatSessionSummary,
  dateLabel: (Long) -> String,
  onOpen: (Long) -> Unit,
  onDelete: (Long) -> Unit,
  onTogglePin: (Long) -> Unit,
) {
  var confirmingDelete by remember(session.id) { mutableStateOf(false) }

  SurfaceCard(Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(HakuRadius.Small)
        .clickable(role = Role.Button, onClickLabel = "เปิดแชท") { onOpen(session.id) },
    ) {
      Text(
        text = session.title,
        style = MaterialTheme.typography.bodyMedium,
        color = HakuColors.Ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      val info = buildString {
        append(dateLabel(session.lastAtMs))
        append(" · ")
        append("${session.turns} ข้อความ")
        if (session.pinned) {
          append(" · ปักหมุดแล้ว")
        }
      }
      Text(
        text = info,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Muted,
        modifier = Modifier.padding(top = 4.dp),
      )
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!confirmingDelete) {
        Text(
          text = if (session.pinned) "เลิกปักหมุด" else "ปักหมุด",
          style = MaterialTheme.typography.labelMedium,
          color = HakuColors.Ink,
          modifier = Modifier
            .clickable(role = Role.Button, onClick = { onTogglePin(session.id) })
            .padding(vertical = 8.dp),
        )
        Text(
          text = "ลบ",
          style = MaterialTheme.typography.labelMedium,
          color = HakuColors.Coral,
          modifier = Modifier
            .clickable(role = Role.Button, onClick = { confirmingDelete = true })
            .padding(vertical = 8.dp),
        )
      } else {
        Text(
          text = "ลบเรื่องนี้?",
          style = MaterialTheme.typography.labelMedium,
          color = HakuColors.Muted,
        )
        Text(
          text = "ลบ",
          style = MaterialTheme.typography.labelMedium,
          color = HakuColors.Coral,
          modifier = Modifier
            .clickable(role = Role.Button, onClick = {
              confirmingDelete = false
              onDelete(session.id)
            })
            .padding(vertical = 8.dp),
        )
        Text(
          text = "ไม่ลบ",
          style = MaterialTheme.typography.labelMedium,
          color = HakuColors.Ink,
          modifier = Modifier
            .clickable(role = Role.Button, onClick = { confirmingDelete = false })
            .padding(vertical = 8.dp),
        )
      }
    }
  }
}
