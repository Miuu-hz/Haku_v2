package com.bbpillow.haku.surface.action

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.ActionStatus
import com.bbpillow.haku.action.AuditRecord
import com.bbpillow.haku.action.PermissionLevel
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionLabel
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

private const val DEFAULT_TZ_OFFSET_MINUTES = 7 * 60

/**
 * P4.1 audit log screen: shows every action call and status transition with Thai labels and Buddhist-era dates
 * ([ThaiDateFormat.dayHeader]), plus an emergency-stop toggle that halts and blocks all actions until resumed.
 */
@Composable
fun AuditScreen(
  records: List<AuditRecord>,
  stopped: Boolean,
  onToggleStop: (Boolean) -> Unit,
  onBack: () -> Unit = {},
  tzOffsetMinutes: Int = DEFAULT_TZ_OFFSET_MINUTES,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Box(
        Modifier
          .size(36.dp)
          .clip(HakuRadius.Pill)
          .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
        contentAlignment = Alignment.Center,
      ) {
        Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
      }
    }
    item {
      ScreenTitle("ประวัติการทำงาน", eyebrow = "ทุกคำสั่งที่ผ่านด่านสิทธิ์ของ Haku")
    }
    item {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text(
              text = "หยุดคำสั่งทั้งหมดฉุกเฉิน",
              style = MaterialTheme.typography.titleSmall,
              color = if (stopped) HakuColors.Coral else HakuColors.Ink,
            )
            Text(
              text = if (stopped) "ระงับทุกคำสั่งอยู่ เปิดเพื่อหยุดบล็อก" else "ยกเลิกคำสั่งที่รอยืนยันและบล็อกคำสั่งใหม่ทันที",
              style = MaterialTheme.typography.bodySmall,
              color = HakuColors.Muted,
            )
          }
          Switch(
            checked = stopped,
            onCheckedChange = onToggleStop,
            colors = SwitchDefaults.colors(
              checkedTrackColor = HakuColors.Coral,
              checkedThumbColor = HakuColors.Surface,
            ),
          )
        }
      }
    }
    item { SectionLabel("รายการล่าสุด", Modifier.padding(top = 4.dp)) }
    item {
      SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
        if (records.isEmpty()) {
          Text(
            text = "ยังไม่มีประวัติการทำงาน",
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Muted,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        }
        records.forEachIndexed { index, record ->
          Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = record.title.ifBlank { record.action },
                style = MaterialTheme.typography.bodyMedium,
                color = HakuColors.Ink,
                modifier = Modifier.weight(1f),
              )
              StatChip(record.status.thaiLabel(), dark = record.status == ActionStatus.DONE)
            }
            Text(
              text = listOf(
                formatAuditTimestamp(record.atUtcMs, tzOffsetMinutes),
                record.origin.thaiLabel(),
                "สิทธิ์: ${record.level.thaiLabel()}",
              ).joinToString(" · "),
              style = MaterialTheme.typography.labelSmall,
              color = if (record.status == ActionStatus.FAILED || record.status == ActionStatus.BLOCKED) {
                HakuColors.Coral
              } else {
                HakuColors.Muted
              },
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          if (index < records.lastIndex) HorizontalDivider(color = HakuColors.Line)
        }
      }
    }
  }
}

internal fun formatAuditTimestamp(atUtcMs: Long, tzOffsetMinutes: Int): String {
  val localIso = MemoryFormat.formatUtc(atUtcMs + tzOffsetMinutes * 60_000L)
  val day = localIso.substring(0, 10)
  val hhmm = localIso.substring(11, 16)
  return "${ThaiDateFormat.dayHeader(day)} $hhmm"
}

internal fun ActionOrigin.thaiLabel(): String = when (this) {
  ActionOrigin.CHAT_RULE -> "แชต"
  ActionOrigin.NOTIFICATION_RULE -> "แจ้งเตือน"
  ActionOrigin.SHARED_TEXT -> "ข้อความที่แชร์"
  ActionOrigin.MODEL_TOOL -> "เครื่องมือ AI"
  ActionOrigin.SKILL -> "ทักษะ"
  ActionOrigin.MCP -> "MCP"
  ActionOrigin.HOST -> "เครื่องที่บ้าน"
}

internal fun PermissionLevel.thaiLabel(): String = when (this) {
  PermissionLevel.AUTO -> "อัตโนมัติ"
  PermissionLevel.NOTIFY -> "แจ้งให้ทราบ"
  PermissionLevel.CONFIRM -> "รอยืนยัน"
  PermissionLevel.BIOMETRIC -> "สแกนนิ้ว"
}

internal fun ActionStatus.thaiLabel(): String = when (this) {
  ActionStatus.PENDING -> "รอยืนยัน"
  ActionStatus.RUNNING -> "กำลังทำ"
  ActionStatus.DONE -> "สำเร็จ"
  ActionStatus.DISMISSED -> "ไม่ได้ทำ"
  ActionStatus.NEEDS_PERMISSION -> "รอสิทธิ์"
  ActionStatus.FAILED -> "ไม่สำเร็จ"
  ActionStatus.BLOCKED -> "ถูกระงับ"
}
