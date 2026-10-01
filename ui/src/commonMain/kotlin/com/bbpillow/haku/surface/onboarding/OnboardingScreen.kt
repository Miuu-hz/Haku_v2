package com.bbpillow.haku.surface.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bbpillow.haku.surface.components.hakuShadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * Callbacks of the onboarding pager. Everything that touches the OS (permission dialogs, model download, biometric
 * prompt) is a callback: the host runs it and reports back through OnboardingPresenter.
 */
class OnboardingActions(
  val onNext: () -> Unit,
  val onBack: () -> Unit,
  val onSkip: () -> Unit,
  val onTogglePermission: (PermissionKind) -> Unit,
  val onRequestPermissions: () -> Unit,
  val onParticle: (EndingParticle) -> Unit,
  val onAddress: (AddressForm) -> Unit,
  val onNickname: (String) -> Unit,
  val onChooseModel: (ModelChoice) -> Unit,
  val onStartDownload: () -> Unit,
  val onPauseDownload: (Boolean) -> Unit,
  val onEnableLock: () -> Unit,
  val onConfirmPlace: (String) -> Unit,
  val onRejectPlace: () -> Unit,
)

/** Asset B1-B7 as one pager: step dots, the step's content, then the primary pill and an optional skip link. */
@Composable
fun OnboardingScreen(
  state: OnboardingUiState,
  actions: OnboardingActions,
  modifier: Modifier = Modifier,
  /** The app icon (Android: the launcher foreground) for the welcome step; null = the violet box tile. */
  logo: Painter? = null,
) {
  Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(72.dp)) { if (state.canGoBack) TextLink("ย้อนกลับ", actions.onBack) }
      StepDots(state, Modifier.weight(1f))
      Box(Modifier.width(72.dp))
    }
    Column(
      Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (state.complete) Done() else when (state.step) {
        OnboardingStep.WELCOME -> Welcome(logo)
        OnboardingStep.PRIVACY -> Privacy()
        OnboardingStep.PERMISSIONS -> Permissions(state, actions)
        OnboardingStep.STYLE -> Style(state, actions)
        OnboardingStep.MODEL -> Model(state, actions)
        OnboardingStep.LOCK -> Lock(state)
        OnboardingStep.FIRST_PLACE -> FirstPlace(state, actions)
      }
    }
    if (!state.complete) BottomBar(state, actions)
  }
}

@Composable
private fun BottomBar(state: OnboardingUiState, actions: OnboardingActions) {
  val (label, action) = when (state.step) {
    OnboardingStep.WELCOME -> "เริ่มต้น" to actions.onNext
    OnboardingStep.PERMISSIONS ->
      if (state.chosenPermissions.all { it in state.grantedPermissions }) "ต่อไป" to actions.onNext
      else "อนุญาต" to actions.onRequestPermissions
    OnboardingStep.STYLE -> "ใช้แบบนี้" to actions.onNext
    OnboardingStep.MODEL -> if (state.downloadProgress == null) "ดาวน์โหลด" to actions.onStartDownload else "ต่อไป" to actions.onNext
    OnboardingStep.LOCK -> if (state.lockEnabled) "ต่อไป" to actions.onNext else "ตั้งค่าเลย" to actions.onEnableLock
    OnboardingStep.FIRST_PLACE -> null to actions.onNext // answered by the sheet's own buttons
    else -> "ต่อไป" to actions.onNext
  }
  Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    if (label != null) CapsuleButton(label, action, Modifier.fillMaxWidth())
    if (state.canSkip) TextLink("ข้ามไปก่อน", actions.onSkip, Modifier.padding(top = 4.dp))
  }
}

@Composable
private fun StepDots(state: OnboardingUiState, modifier: Modifier = Modifier) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
    state.steps.forEachIndexed { i, _ ->
      val on = i == state.index
      Box(Modifier.width(if (on) 30.dp else 18.dp).height(4.dp).background(if (on) HakuColors.Ink else HakuColors.Line, HakuRadius.Pill))
    }
  }
}

@Composable
private fun Hero(icon: ImageVector, tone: HakuTone, title: String, subtitle: String? = null) {
  Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    IconTile(icon, tone, size = 96.dp)
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
    if (subtitle != null) {
      Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
  }
}

@Composable
private fun Welcome(logo: Painter?) {
  if (logo == null) return Hero(HakuIcons.Box, HakuTone.Violet, "Haku", "บันทึกชีวิตให้เอง จำได้ตลอดไป")
  Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    // the launcher foreground has the adaptive-icon safe margin built in, so it sits on a white squircle like the home screen
    Image(
      logo,
      contentDescription = "Haku",
      modifier = Modifier.size(120.dp).hakuShadow(RoundedCornerShape(36.dp)).background(HakuColors.Surface, RoundedCornerShape(36.dp)),
    )
    Text("Haku", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
    Text(
      "บันทึกชีวิตให้เอง จำได้ตลอดไป",
      style = MaterialTheme.typography.bodyMedium,
      color = HakuColors.Muted,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

@Composable
private fun Privacy() {
  Hero(HakuIcons.Lock, HakuTone.Blue, "ข้อมูลของคุณอยู่ในเครื่องนี้เท่านั้น")
  PromiseRow(HakuIcons.Box, HakuTone.Violet, "AI ทำงานในเครื่อง ไม่ส่งขึ้น cloud")
  PromiseRow(HakuIcons.Grid, HakuTone.Mint, "เปิดดูได้ว่าระบบจำอะไร")
  PromiseRow(HakuIcons.File, HakuTone.Coral, "ลบได้ทุกบรรทัด ลบแล้วไม่กลับมา")
}

@Composable
private fun PromiseRow(icon: ImageVector, tone: HakuTone, text: String) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      IconTile(icon, tone, size = 40.dp)
      Text(text, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

private val PermissionTiles = mapOf(
  PermissionKind.LOCATION to (HakuIcons.Route to HakuTone.Coral),
  PermissionKind.ACTIVITY to (HakuIcons.Walk to HakuTone.Mint),
  PermissionKind.CALENDAR to (HakuIcons.File to HakuTone.Amber),
  PermissionKind.BACKGROUND_LOCATION to (HakuIcons.Home to HakuTone.Blue),
)

/** B3 + the Play "prominent disclosure": the reason and the consequence are on screen before any system dialog. */
@Composable
private fun Permissions(state: OnboardingUiState, actions: OnboardingActions) {
  Title("ขออนุญาต ${state.permissions.size} อย่าง พร้อมเหตุผล", "เลือกเฉพาะที่ต้องการ เปลี่ยนใจได้ภายหลังในตั้งค่า")
  state.permissions.forEach { item ->
    val chosen = item.kind in state.chosenPermissions
    val granted = item.kind in state.grantedPermissions
    val (icon, tone) = PermissionTiles.getValue(item.kind)
    SurfaceCard(Modifier.fillMaxWidth()) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon, tone, size = 40.dp)
        Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (granted) {
          StatChip("อนุญาตแล้ว", dark = true)
        } else {
          Switch(
            chosen,
            { actions.onTogglePermission(item.kind) },
            colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
          )
        }
      }
      Text(item.reason, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 8.dp))
      Text(item.ifDenied, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 4.dp))
    }
  }
  Note("ไม่เปิด GPS ตลอดเวลา · ข้อมูลไม่ออกจากเครื่อง · ดูและลบได้ทุกรายการ")
}

/** B4 (D15). The language-level row of the mockup is left out until something uses it. */
@Composable
private fun Style(state: OnboardingUiState, actions: OnboardingActions) {
  Title("อยากให้ Haku พูดกับคุณแบบไหน")
  Question("คำลงท้าย")
  Segmented(
    listOf(EndingParticle.KRAP to "ครับ", EndingParticle.KA to "ค่ะ", EndingParticle.NONE to "ไม่ใช้"),
    state.style.particle,
    actions.onParticle,
  )
  Question("เรียกคุณว่า")
  Segmented(
    listOf(AddressForm.KHUN to "คุณ", AddressForm.NICKNAME to "ชื่อเล่น", AddressForm.NONE to "ไม่เรียก"),
    state.style.address,
    actions.onAddress,
  )
  if (state.style.address == AddressForm.NICKNAME) {
    OutlinedTextField(
      state.style.nickname,
      actions.onNickname,
      Modifier.fillMaxWidth(),
      label = { Text("ชื่อเล่นของคุณ") },
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyMedium,
    )
  }
  HighlightCard(Modifier.fillMaxWidth()) {
    Text("ตัวอย่าง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
    Text(OnboardingPresenter.preview(state.style), style = MaterialTheme.typography.bodyMedium)
  }
}

/** B5 (D7). The download runs in the host; this only shows its progress and the pause switch. */
@Composable
private fun Model(state: OnboardingUiState, actions: OnboardingActions) {
  Title("เลือกสมองให้ Haku")
  StatChip("เครื่องของคุณ: RAM ${state.ramGb} GB")
  state.models.forEach { model ->
    val selected = model == state.model
    val card: @Composable ColumnScope.() -> Unit = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(HakuIcons.Box, HakuTone.Violet, size = 40.dp)
        Column {
          Text("${model.title} · ${model.sizeGb} GB", style = MaterialTheme.typography.titleSmall)
          Text(model.note, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
    }
    val pick = Modifier.fillMaxWidth().clip(HakuRadius.Card).clickable(role = Role.RadioButton) { actions.onChooseModel(model) }
    if (selected) HighlightCard(pick, card) else SurfaceCard(pick, content = card)
  }
  state.downloadProgress?.let { p ->
    LinearProgressIndicator(
      progress = { p },
      modifier = Modifier.fillMaxWidth().height(10.dp).clip(HakuRadius.Pill),
      color = HakuColors.Violet,
      trackColor = HakuColors.Track,
      strokeCap = StrokeCap.Round,
      drawStopIndicator = {},
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      val text = when {
        p >= 1f -> "ดาวน์โหลดเสร็จแล้ว"
        state.downloadPaused -> "หยุดไว้ที่ ${(p * 100).toInt()}%"
        else -> "ดาวน์โหลด ${(p * 100).toInt()}%"
      }
      Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
      if (p < 1f) TextLink(if (state.downloadPaused) "ดาวน์โหลดต่อ" else "หยุดชั่วคราว", { actions.onPauseDownload(!state.downloadPaused) })
    }
  }
  Note("ดาวน์โหลดครั้งเดียว ใช้ได้แม้ไม่มีเน็ต · ไปขั้นต่อไปได้ระหว่างดาวน์โหลด")
}

/** B6 (D11): the system prompt decides; the app only learns pass/fail. */
@Composable
private fun ColumnScope.Lock(state: OnboardingUiState) {
  Hero(HakuIcons.Lock, HakuTone.Blue, "ล็อกความจำของคุณ", "ต้องสแกนนิ้วก่อนเปิดแอป")
  if (state.lockEnabled) StatChip("เปิดล็อกแล้ว", Modifier.align(Alignment.CenterHorizontally), dark = true)
}

/** B7: yes (the guessed label), no, or a different name. The map card waits for a map source. */
@Composable
private fun ColumnScope.FirstPlace(state: OnboardingUiState, actions: OnboardingActions) {
  val guess = state.placeGuess ?: return
  var renaming by remember { mutableStateOf(false) }
  var name by remember { mutableStateOf("") }
  val label = OnboardingPresenter.placeLabel(guess.category)
  Hero(HakuIcons.Home, HakuTone.Coral, OnboardingPresenter.placeQuestion(guess.category))
  val window = if (guess.category == PlaceCategory.WORK) "ช่วงเวลาทำงาน" else "ตอนกลางคืน"
  Note("คุณอยู่ที่นี่$window ${guess.evidenceDays} วันที่ผ่านมา")
  if (renaming) {
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("ชื่อที่นี่") }, singleLine = true)
    CapsuleButton("บันทึก", { actions.onConfirmPlace(name) }, Modifier.fillMaxWidth())
  } else {
    CapsuleButton("ใช่ $label", { actions.onConfirmPlace(label) }, Modifier.fillMaxWidth())
    CapsuleButton("ไม่ใช่", actions.onRejectPlace, Modifier.fillMaxWidth(), outline = true)
    TextLink("ตั้งชื่ออื่น", { renaming = true }, Modifier.align(Alignment.CenterHorizontally))
  }
}

@Composable
private fun Done() = Hero(HakuIcons.Home, HakuTone.Mint, "พร้อมแล้ว", "ใช้ชีวิตตามปกติ แล้วเย็นนี้กลับมาดูเรื่องราวของวันนี้")

@Composable
private fun Title(text: String, subtitle: String? = null) {
  Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
  }
}

@Composable
private fun Question(text: String) =
  Text(text, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(start = 4.dp))

@Composable
private fun Note(text: String) =
  Text(text, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = HakuColors.Muted,
    modifier = modifier.clip(HakuRadius.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
  )
}

/** .seg: white pill track, the chosen option is a black pill. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
  Row(Modifier.fillMaxWidth().background(HakuColors.Surface, HakuRadius.Pill).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    options.forEach { (value, label) ->
      val on = value == selected
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) HakuColors.Surface else HakuColors.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f).clip(HakuRadius.Pill)
          .background(if (on) HakuColors.Ink else HakuColors.Surface)
          .clickable(role = Role.RadioButton) { onSelect(value) }
          .padding(vertical = 10.dp),
      )
    }
  }
}
