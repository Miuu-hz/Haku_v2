package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionLabel
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * Settings screen ("ตั้งค่า") opened from the gear icon on the Memory tab.
 * Sections: (a) AI ในเครื่อง, (b) คำลงท้าย, (c) สรุปประจำวัน (P5.2, both off by default), (d) ล็อกแอป,
 * (e) ความสามารถเสริม, (f) ความเป็นส่วนตัว (P5.8 notification access, P4.1 audit, PDPA notice). State-hoisted.
 */
@Composable
fun SettingsScreen(
  state: SettingsUiState,
  onBack: () -> Unit,
  onOpenLlmSettings: () -> Unit,
  onParticle: (EndingParticle) -> Unit,
  onAddress: (AddressForm) -> Unit,
  onNickname: (String) -> Unit,
  onToggleLock: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onToggleBrief: (BriefKind, Boolean) -> Unit = { _, _ -> },
  onPickBriefTime: (BriefKind) -> Unit = {},
  onOpenExtensions: () -> Unit = {},
  notificationAccessOn: Boolean = false,
  onOpenNotificationAccess: () -> Unit = {},
  onOpenAudit: () -> Unit = {},
  onOpenPrivacyNotice: () -> Unit = {},
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  Column(
    modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier
        .size(36.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }

    ScreenTitle("ตั้งค่า")

    SectionLabel("AI ในเครื่อง")
    NavRow(HakuIcons.Box, HakuTone.Violet, "AI ในเครื่อง", state.activeModelLabel, onOpenLlmSettings)

    SectionLabel("คำลงท้าย", Modifier.padding(top = 4.dp))
    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("คำลงท้าย", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        StyleSegmented(
          options = listOf(EndingParticle.KRAP to "ครับ", EndingParticle.KA to "ค่ะ", EndingParticle.NONE to "ไม่ใช้"),
          selected = state.style.particle,
          onSelect = onParticle,
        )
        Text("เรียกคุณว่า", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        StyleSegmented(
          options = listOf(AddressForm.KHUN to "คุณ", AddressForm.NICKNAME to "ชื่อเล่น", AddressForm.NONE to "ไม่เรียก"),
          selected = state.style.address,
          onSelect = onAddress,
        )
        if (state.style.address == AddressForm.NICKNAME) {
          OutlinedTextField(
            value = state.style.nickname,
            onValueChange = onNickname,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ชื่อเล่นของคุณ") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    HighlightCard(Modifier.fillMaxWidth()) {
      Text("ตัวอย่าง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      Text(state.stylePreview, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
    }

    SectionLabel("สรุปประจำวัน", Modifier.padding(top = 4.dp))
    SurfaceCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BriefRow("สรุปเช้า", "เมื่อวานเป็นยังไง และนัดของวันนี้", state.morningBriefOn, state.morningBriefTime,
          { onToggleBrief(BriefKind.MORNING, it) }, { onPickBriefTime(BriefKind.MORNING) })
        BriefRow("สรุปเย็น", "วันนี้เป็นยังไง ชวนเล่าเพิ่มถ้าอยาก", state.eveningBriefOn, state.eveningBriefTime,
          { onToggleBrief(BriefKind.EVENING, it) }, { onPickBriefTime(BriefKind.EVENING) })
        Text(
          "แจ้งเตือนวันละไม่เกินหนึ่งครั้งต่อแบบ ไม่มีอะไรจะเล่าก็ไม่แจ้ง หน้าจอล็อกไม่แสดงเนื้อหา สร้างในเครื่องนี้ทั้งหมด",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
        )
      }
    }

    SectionLabel("ล็อกแอป", Modifier.padding(top = 4.dp))
    SurfaceCard(Modifier.fillMaxWidth()) {
      Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        IconTile(HakuIcons.Lock, HakuTone.Blue, size = 40.dp)
        Column(Modifier.weight(1f)) {
          Text("ล็อกแอป", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
          Text("ต้องสแกนนิ้วก่อนเปิดแอป", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
        Switch(
          checked = state.lockEnabled,
          onCheckedChange = onToggleLock,
          colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
        )
      }
    }

    SectionLabel("ความสามารถเสริม", Modifier.padding(top = 4.dp))
    NavRow(HakuIcons.Spark, HakuTone.Violet, "ความสามารถเสริม", "ทักษะในเครื่องและบริการภายนอก (MCP)", onOpenExtensions)

    SectionLabel("ความเป็นส่วนตัว", Modifier.padding(top = 4.dp))
    // P5.8: the reason is on screen before the system page opens (Play prominent disclosure, PDPA s.23)
    NavRow(
      HakuIcons.File, HakuTone.Amber, "อ่านนัดจากแจ้งเตือนแชท",
      (if (notificationAccessOn) "สิทธิ์ระบบเปิดอยู่ · " else "สิทธิ์ระบบปิดอยู่ · ") +
        "เลือกได้ทีละแอป อ่านเฉพาะข้อความที่ดูเหมือนนัด กรองในเครื่อง ไม่เก็บข้อความแชท",
      onOpenNotificationAccess,
    )
    NavRow(HakuIcons.Grid, HakuTone.Mint, "ประวัติการทำงาน", "ทุกคำสั่งที่ Haku ทำแทนคุณ และปุ่มหยุดทั้งหมด", onOpenAudit)
    NavRow(HakuIcons.Lock, HakuTone.Blue, "ประกาศความเป็นส่วนตัว", "เก็บอะไร ไว้ที่ไหน นานเท่าไร และสิทธิ์ของคุณ", onOpenPrivacyNotice)

    state.message?.let { msg ->
      Text(
        msg,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Coral,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
    }
  }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: HakuTone, title: String, detail: String, onClick: () -> Unit) {
  SurfaceCard(Modifier.fillMaxWidth().clip(HakuRadius.Card).clickable(role = Role.Button, onClickLabel = title, onClick = onClick)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      IconTile(icon, tone, size = 40.dp)
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      }
      Icon(HakuIcons.Chevron, contentDescription = null, tint = HakuColors.Muted, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun BriefRow(
  title: String,
  detail: String,
  on: Boolean,
  time: String,
  onToggle: (Boolean) -> Unit,
  onPickTime: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
      Text(detail, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    }
    Text(
      time,
      style = MaterialTheme.typography.labelLarge,
      color = HakuColors.Ink,
      modifier = Modifier
        .clip(HakuRadius.Pill)
        .background(HakuColors.Bg)
        .clickable(role = Role.Button, onClickLabel = "เปลี่ยนเวลา$title", onClick = onPickTime)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    Switch(
      checked = on,
      onCheckedChange = onToggle,
      colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
    )
  }
}

@Composable
internal fun <T> StyleSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(HakuColors.Bg, HakuRadius.Pill)
      .padding(4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    options.forEach { (value, label) ->
      val on = value == selected
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) HakuColors.Surface else HakuColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .weight(1f)
          .clip(HakuRadius.Pill)
          .background(if (on) HakuColors.Ink else HakuColors.Bg)
          .clickable(role = Role.RadioButton) { onSelect(value) }
          .padding(vertical = 10.dp),
      )
    }
  }
}
