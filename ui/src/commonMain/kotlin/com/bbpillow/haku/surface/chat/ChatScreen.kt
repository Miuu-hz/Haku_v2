package com.bbpillow.haku.surface.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.components.hakuShadow
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** One question and its answer; [answer] = null while Haku is still looking. */
data class ChatTurn(val question: String, val answer: ChatAnswerOutput?)

private val UserBubble = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
private val AnswerBubble = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

/** Questions offered on an empty chat: each one is answerable from the diary alone. */
val ChatSuggestions = listOf("ครั้งล่าสุดที่ไปร้านกาแฟคือเมื่อไร", "เมื่อวานไปไหนมา", "อาทิตย์ที่แล้วไปไหนบ้าง")

/**
 * Screen 3 of haku-mockup.html: black question bubbles, white answer cards whose source lines carry a date chip
 * (tap = open that day's timeline), and a pill input. [engineLabel] shows the active model's short name or
 * "ยังไม่มีโมเดล" when none is ready; tapping it opens the model manager. State-hoisted: the caller runs ChatPresenter.
 */
@Composable
fun ChatScreen(
  turns: List<ChatTurn>,
  engineLabel: String,
  onAsk: (String) -> Unit,
  onOpenDay: (String) -> Unit,
  onOpenModels: () -> Unit = {},
  onAddAppointment: (Int) -> Unit = {},
  onDismissAppointment: (Int) -> Unit = {},
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var draft by remember { mutableStateOf("") }
  val busy = turns.lastOrNull()?.answer == null && turns.isNotEmpty()
  val send = { if (draft.isNotBlank() && !busy) { onAsk(draft.trim()); draft = "" } }
  val list = rememberLazyListState()
  LaunchedEffect(turns.size, turns.lastOrNull()?.answer) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

  // Keyboard open: lift the input above it; the nav padding counts as already-consumed space so there is no double gap.
  Column(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ScreenTitle("ถาม Haku", Modifier.weight(1f), eyebrow = "ตอบจากบันทึกของคุณ พร้อมวันที่อ้างอิง")
          StatChip(
            engineLabel,
            Modifier
              .padding(top = 30.dp)
              .clip(HakuRadius.Pill)
              .clickable(role = Role.Button, onClickLabel = "AI ในเครื่อง", onClick = onOpenModels),
          )
        }
      }
      if (turns.isEmpty()) {
        item { Suggestions { if (!busy) onAsk(it) } }
      }
      items(turns.size) { i ->
        val turn = turns[i]
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
              turn.question,
              style = MaterialTheme.typography.bodyMedium,
              color = HakuColors.Surface,
              modifier = Modifier.widthIn(max = 300.dp).background(HakuColors.Ink, UserBubble).padding(horizontal = 16.dp, vertical = 11.dp),
            )
          }
          val appointment = turn.answer?.appointment
          if (appointment != null) {
            if (turn.answer?.text?.isNotBlank() == true) {
              AnswerCard(turn.answer, onOpenDay)
            }
            AppointmentCard(
              proposal = appointment,
              onAdd = { onAddAppointment(i) },
              onDismiss = { onDismissAppointment(i) },
            )
          } else {
            AnswerCard(turn.answer, onOpenDay)
          }
        }
      }
    }
    InputPill(draft, { draft = it }, send, Modifier.padding(top = 12.dp))
  }
}

@Composable
private fun Suggestions(onAsk: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
    Text("ลองถาม", style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.padding(start = 4.dp))
    ChatSuggestions.forEach { q ->
      SurfaceCard(
        Modifier.fillMaxWidth().clip(HakuRadius.Card).clickable(role = Role.Button) { onAsk(q) },
        padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
      ) {
        Text(q, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
      }
    }
  }
}

@Composable
private fun AnswerCard(answer: ChatAnswerOutput?, onOpenDay: (String) -> Unit) {
  Column(
    Modifier.widthIn(max = 340.dp).hakuShadow(AnswerBubble).background(HakuColors.Surface, AnswerBubble).padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    if (answer == null) {
      Text("กำลังค้นในบันทึก…", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
      return@Column
    }
    Text(answer.text, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Ink)
    answer.citations.forEachIndexed { i, c ->
      if (i == 0) HorizontalDivider(Modifier.padding(top = 10.dp), color = HakuColors.Line)
      Row(
        Modifier.fillMaxWidth()
          .then(if (c.day != null) Modifier.clickable(role = Role.Button, onClickLabel = "เปิดวันนี้ในไทม์ไลน์") { onOpenDay(c.day!!) } else Modifier)
          .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(c.text, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink, modifier = Modifier.weight(1f))
        StatChip(c.label)
      }
      if (i < answer.citations.lastIndex) HorizontalDivider(color = HakuColors.Line)
    }
  }
}

/** .input: white pill, grey placeholder, black round send button. */
@Composable
private fun InputPill(text: String, onText: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().hakuShadow(HakuRadius.Pill).background(HakuColors.Surface, HakuRadius.Pill).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.weight(1f)) {
      if (text.isEmpty()) Text("พิมพ์คำถาม…", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
      BasicTextField(
        text,
        onText,
        Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = HakuColors.Ink),
        cursorBrush = SolidColor(HakuColors.Violet),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        maxLines = 3,
      )
    }
    Box(
      Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape).background(HakuColors.Ink).clickable(role = Role.Button, onClickLabel = "ส่ง", onClick = onSend),
      contentAlignment = Alignment.Center,
    ) { Icon(HakuIcons.Send, "ส่ง", tint = HakuColors.Surface, modifier = Modifier.size(20.dp)) }
  }
}
