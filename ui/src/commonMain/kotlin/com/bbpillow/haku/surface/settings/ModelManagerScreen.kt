package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ConfirmDialog
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * On-device AI model manager ("AI ในเครื่อง"): built-in catalog models + user-imported `.litertlm` files.
 * State-hoisted: all download, SAF import and file deletion effects live in `androidApp`.
 */
@Composable
fun ModelManagerScreen(
  state: ModelManagerUiState,
  onBack: () -> Unit,
  onDownload: (ModelChoice) -> Unit,
  onPauseResume: (ModelChoice, Boolean) -> Unit,
  onSetActive: (ActiveModel) -> Unit,
  onDelete: (ModelCardItem) -> Unit,
  onImport: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var deleting by remember { mutableStateOf<ModelCardItem?>(null) }

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

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      ScreenTitle("AI ในเครื่อง", Modifier.weight(1f), eyebrow = "เลือก ดาวน์โหลด หรือนำเข้าโมเดล")
      StatChip("RAM ${state.ramGb} GB", Modifier.padding(top = 30.dp))
    }

    state.message?.let { msg ->
      SurfaceCard(Modifier.fillMaxWidth(), color = HakuColors.Peach) {
        Text(msg, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
      }
    }

    state.models.forEach { card ->
      ModelCard(
        card = card,
        onDownload = { card.choice?.let(onDownload) },
        onPauseResume = { pause -> card.choice?.let { onPauseResume(it, pause) } },
        onSetActive = { onSetActive(card.ref) },
        onRequestDelete = { deleting = card },
      )
    }

    if (state.importingFileName != null) {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Text("กำลังนำเข้า ${state.importingFileName}…", style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
          progress = { state.importFraction },
          modifier = Modifier.fillMaxWidth().height(8.dp).clip(HakuRadius.Pill),
          color = HakuColors.Violet,
          trackColor = HakuColors.Track,
          strokeCap = StrokeCap.Round,
          drawStopIndicator = {},
        )
      }
    }

    CapsuleButton(
      text = "นำเข้าไฟล์โมเดล",
      onClick = onImport,
      modifier = Modifier.fillMaxWidth(),
      outline = true,
    )

    Text(
      ModelManagerPresenter.FOOTER_NOTE,
      style = MaterialTheme.typography.bodySmall,
      color = HakuColors.Muted,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
    )
  }

  deleting?.let { item ->
    ConfirmDialog(
      title = "ลบโมเดล \"${item.title}\"?",
      message = "ไฟล์โมเดลในเครื่องจะถูกลบ ความจำและบันทึกประจำวันของคุณจะไม่ถูกแตะต้อง",
      confirmText = "ลบ",
      onConfirm = {
        onDelete(item)
        deleting = null
      },
      onDismiss = { deleting = null },
    )
  }
}

@Composable
private fun ModelCard(
  card: ModelCardItem,
  onDownload: () -> Unit,
  onPauseResume: (Boolean) -> Unit,
  onSetActive: () -> Unit,
  onRequestDelete: () -> Unit,
) {
  val body: @Composable ColumnScope.() -> Unit = {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      IconTile(HakuIcons.Box, if (card.isImported) HakuTone.Mint else HakuTone.Violet, size = 40.dp)
      Column(Modifier.weight(1f)) {
        val meta = buildString {
          append(card.sizeLabel)
          card.minRamGb?.let { append(" · RAM ${it} GB+") }
        }
        Text("${card.title} · $meta", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
        val subtitle = card.note ?: if (card.isImported) "นำเข้าจากไฟล์ .litertlm" else null
        if (subtitle != null) {
          Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
      when (card.status) {
        ModelStatus.ACTIVE -> StatChip("ใช้งานอยู่", dark = true)
        ModelStatus.DOWNLOADED -> StatChip("พร้อมใช้")
        ModelStatus.DOWNLOADING, ModelStatus.NOT_DOWNLOADED -> {}
      }
    }

    if (card.ramWarning) {
      Text(
        ModelManagerPresenter.RAM_WARNING_TEXT,
        style = MaterialTheme.typography.bodySmall,
        color = HakuColors.Coral,
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    when (card.status) {
      ModelStatus.NOT_DOWNLOADED -> {
        Row(
          Modifier.fillMaxWidth().padding(top = 12.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CapsuleButton("ดาวน์โหลด", onDownload)
        }
      }
      ModelStatus.DOWNLOADING -> {
        val pct = (card.downloadFraction * 100).toInt()
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          LinearProgressIndicator(
            progress = { card.downloadFraction },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(HakuRadius.Pill),
            color = HakuColors.Violet,
            trackColor = HakuColors.Track,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
          )
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val statusText = if (card.downloadPaused) "หยุดไว้ที่ $pct%" else "กำลังดาวน์โหลด $pct%"
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.weight(1f))
            ActionLink(if (card.downloadPaused) "ต่อ" else "หยุดชั่วคราว") { onPauseResume(!card.downloadPaused) }
            ActionLink("ลบ", danger = true, onClick = onRequestDelete)
          }
        }
      }
      ModelStatus.DOWNLOADED -> {
        Row(
          Modifier.fillMaxWidth().padding(top = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ActionLink("ลบ", danger = true, onClick = onRequestDelete)
          CapsuleButton("ใช้ตัวนี้", onSetActive)
        }
      }
      ModelStatus.ACTIVE -> {
        Row(
          Modifier.fillMaxWidth().padding(top = 10.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ActionLink("ลบ", danger = true, onClick = onRequestDelete)
        }
      }
    }
  }

  if (card.status == ModelStatus.ACTIVE) {
    HighlightCard(Modifier.fillMaxWidth(), content = body)
  } else {
    SurfaceCard(Modifier.fillMaxWidth(), content = body)
  }
}

@Composable
private fun ActionLink(text: String, danger: Boolean = false, onClick: () -> Unit) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = if (danger) HakuColors.Coral else HakuColors.Ink,
    modifier = Modifier
      .clip(HakuRadius.Pill)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  )
}
