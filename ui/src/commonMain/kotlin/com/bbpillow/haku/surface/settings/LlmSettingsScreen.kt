package com.bbpillow.haku.surface.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone
import com.bbpillow.haku.thinking.LlmSettings
import com.bbpillow.haku.thinking.ModelBackend
import com.bbpillow.haku.thinking.ModelLoadPolicy
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun LlmSettingsScreen(
  state: LlmSettingsUiState,
  onBack: () -> Unit,
  onOpenModels: () -> Unit,
  onChange: (LlmSettings) -> Unit,
  onResetDefaults: () -> Unit,
  modifier: Modifier = Modifier,
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
        .size(44.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }

    ScreenTitle("AI ในเครื่อง")

    state.notice?.let { notice ->
      SurfaceCard(Modifier.fillMaxWidth(), color = HakuColors.Peach) {
        Text(notice, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
      }
    }

    ModelCard(state.modelLabel, onOpenModels)
    MainCard(state, onChange)
    AdvancedCard(state.settings, onChange, onResetDefaults)
    MetricsBand(state.metrics)
  }
}

@Composable
private fun ModelCard(modelLabel: String, onOpenModels: () -> Unit) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      IconTile(HakuIcons.Box, HakuTone.Violet, size = 40.dp)
      Text(
        text = modelLabel,
        style = MaterialTheme.typography.titleSmall,
        color = HakuColors.Ink,
        modifier = Modifier.weight(1f),
      )
      CapsuleButton("เปลี่ยนโมเดล", onOpenModels, outline = true)
    }
  }
}

@Composable
private fun MainCard(state: LlmSettingsUiState, onChange: (LlmSettings) -> Unit) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
      ContextLengthRow(state, onChange)
      BackendRow(state.settings.backend) { onChange(state.settings.copy(backend = it)) }
      MtpRow(state.settings.speculativeDecoding) { onChange(state.settings.copy(speculativeDecoding = it)) }
      LoadPolicyRow(state.settings.loadPolicy) { onChange(state.settings.copy(loadPolicy = it)) }
      IdleReleaseRow(state.settings.idleReleaseSeconds) { onChange(state.settings.copy(idleReleaseSeconds = it)) }
    }
  }
}

@Composable
private fun ContextLengthRow(state: LlmSettingsUiState, onChange: (LlmSettings) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("ความยาวบริบท", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
      Text(state.recommendedLabel, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    }
    val selectedTokens = state.contextOptions.firstOrNull { it.selected }?.tokens ?: state.settings.contextTokens
    StyleSegmented(
      options = state.contextOptions.map { it.tokens to it.label },
      selected = selectedTokens,
      onSelect = { onChange(state.settings.copy(contextTokens = it)) },
    )
    Text(state.contextNote, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
  }
}

@Composable
private fun BackendRow(selected: ModelBackend, onSelect: (ModelBackend) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("ตัวประมวลผล", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
    Text("GPU เร็วกว่า · CPU ใช้เมื่อ GPU เปิดไม่ได้", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    StyleSegmented(
      options = listOf(ModelBackend.Gpu to "GPU", ModelBackend.Cpu to "CPU"),
      selected = selected,
      onSelect = onSelect,
    )
  }
}

@Composable
private fun MtpRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text("เร่งความเร็ว (MTP)", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
      Text(
        "บางงานตอบเร็วขึ้น · ใช้แรมเพิ่มราว 140 MB · โหลดช้าลง",
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Muted,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
    )
  }
}

@Composable
private fun LoadPolicyRow(selected: ModelLoadPolicy, onSelect: (ModelLoadPolicy) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("โหลดโมเดลเมื่อ", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
    Text("ออกจากแอปแล้วคืนแรมทันที", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    StyleSegmented(
      options = listOf(
        ModelLoadPolicy.ON_APP_OPEN to "เปิดแอป",
        ModelLoadPolicy.ON_CHAT_OPEN to "เปิดแชท",
        ModelLoadPolicy.ON_ASK to "ตอนถาม",
      ),
      selected = selected,
      onSelect = onSelect,
    )
  }
}

@Composable
private fun IdleReleaseRow(selected: Int, onSelect: (Int) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("คืนแรมเมื่อไม่ได้ถาม", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
    StyleSegmented(
      options = listOf(30 to "30 วิ", 60 to "1 นาที", 300 to "5 นาที"),
      selected = selected,
      onSelect = onSelect,
    )
  }
}

@Composable
private fun AdvancedCard(
  settings: LlmSettings,
  onChange: (LlmSettings) -> Unit,
  onResetDefaults: () -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }

  SurfaceCard(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(
        Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 44.dp)
          .clickable(
            role = Role.Button,
            onClickLabel = if (expanded) "ย่อขั้นสูง" else "ขยายขั้นสูง",
            onClick = { expanded = !expanded },
          ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("ขั้นสูง", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink, modifier = Modifier.weight(1f))
        Icon(
          HakuIcons.Chevron,
          contentDescription = null,
          tint = HakuColors.Muted,
          modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
        )
      }
      if (expanded) {
        SliderRow(
          label = "Temperature",
          valueText = formatOneDecimal(settings.temperature),
          value = settings.temperature.toFloat(),
          range = 0f..2f,
          onValueChange = { onChange(settings.copy(temperature = roundToOneDecimal(it))) },
        )
        SliderRow(
          label = "Top-K",
          valueText = "${settings.topK}",
          value = settings.topK.toFloat(),
          range = 1f..100f,
          onValueChange = { onChange(settings.copy(topK = it.roundToInt())) },
        )
        SliderRow(
          label = "Top-P",
          valueText = formatTwoDecimals(settings.topP),
          value = settings.topP.toFloat(),
          range = 0f..1f,
          onValueChange = { onChange(settings.copy(topP = roundToTwoDecimals(it))) },
        )
        CapsuleButton("คืนค่าเริ่มต้น", onResetDefaults, modifier = Modifier.fillMaxWidth(), outline = true)
        Text("ค่าเหล่านี้มีผลกับข้อความถัดไป", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
      }
    }
  }
}

@Composable
private fun SliderRow(
  label: String,
  valueText: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
      Text(valueText, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    }
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onValueChange,
      valueRange = range,
      colors = SliderDefaults.colors(
        thumbColor = HakuColors.Ink,
        activeTrackColor = HakuColors.Ink,
        inactiveTrackColor = HakuColors.Track,
      ),
    )
  }
}

@Composable
private fun MetricsBand(metrics: List<Pair<String, String>>) {
  SurfaceCard(Modifier.fillMaxWidth(), color = HakuColors.Ink) {
    Row(Modifier.fillMaxWidth()) {
      metrics.forEach { (label, value) ->
        Column(Modifier.weight(1f)) {
          Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Surface.copy(alpha = 0.7f),
          )
          Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = HakuColors.Surface,
          )
        }
      }
    }
  }
}

private fun roundToOneDecimal(value: Float): Double =
  round(value * 10f).toDouble() / 10.0

private fun roundToTwoDecimals(value: Float): Double =
  round(value * 100f).toDouble() / 100.0

private fun formatOneDecimal(value: Double): String {
  val tenths = round(value * 10).toLong()
  return "${tenths / 10}.${tenths % 10}"
}

private fun formatTwoDecimals(value: Double): String {
  val hundredths = round(value * 100).toLong()
  val frac = (hundredths % 100).toString().padStart(2, '0')
  return "${hundredths / 100}.$frac"
}
