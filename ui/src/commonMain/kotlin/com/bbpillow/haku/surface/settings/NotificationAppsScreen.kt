package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.capture.notification.NotificationApp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

@Composable
fun NotificationAppsScreen(
  apps: List<NotificationApp>,
  enabled: Set<String>,
  systemAccessOn: Boolean,
  onToggle: (packageName: String, on: Boolean) -> Unit,
  onOpenSystemAccess: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
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

    ScreenTitle("แจ้งเตือนจากแอปแชท")

    SurfaceCard(Modifier.fillMaxWidth(), color = HakuColors.Mint) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        IconTile(HakuIcons.Lock, HakuTone.Mint, size = 36.dp)
        Text(
          text = "Haku อ่านเฉพาะแอปที่เปิดไว้ เพื่อเสนอนัดให้คุณกดเพิ่มเอง ข้อความไม่ออกนอกเครื่องและไม่ถูกเก็บ · ข้ามแจ้งเตือนไลฟ์เสมอ",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Ink,
          modifier = Modifier.weight(1f),
        )
      }
    }

    if (!systemAccessOn) {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = "ยังไม่ได้เปิดสิทธิ์อ่านแจ้งเตือนในระบบ สวิตช์ด้านล่างจะยังไม่ทำงาน",
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Ink,
            modifier = Modifier.weight(1f),
          )
          CapsuleButton("เปิดสิทธิ์", onOpenSystemAccess)
        }
      }
    }

    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        apps.forEach { app ->
          AppRow(
            app = app,
            checked = app.packageName in enabled,
            onToggle = { onToggle(app.packageName, it) },
          )
        }
      }
    }
  }
}

@Composable
private fun AppRow(
  app: NotificationApp,
  checked: Boolean,
  onToggle: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 48.dp)
      .toggleable(
        value = checked,
        role = Role.Switch,
        onValueChange = onToggle,
      ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(tileColor(app.packageName)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = app.mono,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = app.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = HakuColors.Ink,
      )
      Text(
        text = app.note,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Muted,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = null,
      colors = SwitchDefaults.colors(
        checkedTrackColor = HakuColors.Ink,
        checkedThumbColor = HakuColors.Surface,
      ),
    )
  }
}

private fun tileColor(packageName: String): Color = when (packageName) {
  "jp.naver.line.android" -> Color(0xFF2FB45A)
  "com.facebook.orca" -> Color(0xFF4A78F0)
  "com.whatsapp" -> Color(0xFF24A866)
  "org.telegram.messenger" -> Color(0xFF2C8FD6)
  "com.google.android.apps.messaging" -> Color(0xFF3F6FD8)
  "com.samsung.android.messaging" -> Color(0xFF5A64D8)
  "com.instagram.android" -> Color(0xFFC2407A)
  "com.twitter.android" -> Color(0xFF17171C)
  "com.linkedin.android" -> Color(0xFF2867B2)
  "com.discord" -> Color(0xFF5865C8)
  "com.Slack" -> Color(0xFF611F69)
  "com.microsoft.teams" -> Color(0xFF4B53BC)
  "com.viber.voip" -> Color(0xFF7360F2)
  "com.kakao.talk" -> Color(0xFF8A7A12)
  "com.tencent.mm" -> Color(0xFF1F9D3A)
  else -> HakuColors.Muted
}
