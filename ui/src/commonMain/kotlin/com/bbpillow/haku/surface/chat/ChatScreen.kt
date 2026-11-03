package com.bbpillow.haku.surface.chat

import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.skill.MentionOption
import com.bbpillow.haku.skill.MentionParser
import com.bbpillow.haku.skill.SkillRecord
import com.bbpillow.haku.surface.action.ActionCalendarIcon
import com.bbpillow.haku.surface.action.ActionCard
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.components.hakuShadow
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

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
  /** Week 2: gate proposals by id (cards under skill answers) and the skills the `/` `@` popup can offer. */
  actions: Map<String, ActionProposalRecord> = emptyMap(),
  onConfirmAction: (String) -> Unit = {},
  onDismissAction: (String) -> Unit = {},
  mentionSkills: List<SkillRecord> = emptyList(),
  /** MCP server host -> display name, for the `@` popup rows. */
  mentionServiceNames: Map<String, String> = emptyMap(),
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  // TextFieldValue, not String: a picked mention must leave the cursor after it
  var draft by remember { mutableStateOf(TextFieldValue("")) }
  val busy = turns.lastOrNull()?.answer == null && turns.isNotEmpty()
  val send = { if (draft.text.isNotBlank() && !busy) { onAsk(draft.text.trim()); draft = TextFieldValue("") } }
  val mentions = remember(draft.text, mentionSkills, mentionServiceNames) { MentionParser.options(draft.text, mentionSkills, mentionServiceNames) }
  val list = rememberLazyListState()
  LaunchedEffect(turns.size, turns.lastOrNull()?.answer) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

  // Keyboard open: lift the input above it; the nav padding counts as already-consumed space so there is no double gap.
  Column(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
              turn.answer?.actionId?.let(actions::get)?.let { p ->
                ActionCard(p, onConfirm = { onConfirmAction(p.id) }, onDismiss = { onDismissAction(p.id) })
              }
            }
          }
        }
      }
      MentionPopup(mentions, Modifier.align(Alignment.BottomStart)) { option ->
        val trigger = if (draft.text.trimStart().startsWith(MentionParser.SKILL)) MentionParser.SKILL else MentionParser.SERVICE
        val filled = "$trigger${option.key} "
        draft = TextFieldValue(filled, TextRange(filled.length))
      }
    }
    InputPill(draft, { draft = it }, send, Modifier.padding(top = 12.dp))
  }
}

/** Gemini-style app list floating above the input: one row per local skill or outside service; a tap fills the mention. */
@Composable
private fun MentionPopup(options: List<MentionOption>, modifier: Modifier = Modifier, onPick: (MentionOption) -> Unit) {
  if (options.isEmpty()) return
  LazyColumn(
    modifier.widthIn(max = 340.dp).fillMaxWidth().heightIn(max = MENTION_POPUP_MAX)
      .hakuShadow(HakuRadius.Card).background(HakuColors.Surface, HakuRadius.Card).clip(HakuRadius.Card),
    contentPadding = PaddingValues(vertical = 6.dp),
  ) {
    items(options) { o ->
      val local = o.kind == MentionOption.Kind.LOCAL
      Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "ใช้ ${o.label}") { onPick(o) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        val icon = when {
          !local -> HakuIcons.Spark
          o.skills.any { it.action == CalendarAddArgs.ACTION } -> ActionCalendarIcon
          else -> HakuIcons.Grid
        }
        IconTile(icon, if (local) HakuTone.Blue else HakuTone.Violet, size = 40.dp)
        Column {
          Text(o.label, style = MaterialTheme.typography.titleMedium, color = HakuColors.Ink)
          if (!local) Text("บริการภายนอก · ส่งข้อมูลออกจากเครื่อง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
    }
  }
}

// ponytail: ~6.5 rows of 60dp so the half row hints at scrolling
private val MENTION_POPUP_MAX = 400.dp

/** Colors a leading `@word` / `/word` so the picked app stands out; style only, so offsets map 1:1. */
private val MentionHighlight = VisualTransformation { t ->
  val end = LEADING_MENTION.find(t.text)?.range?.last?.plus(1) ?: 0
  if (end == 0) TransformedText(t, OffsetMapping.Identity)
  else TransformedText(buildAnnotatedString { append(t); addStyle(SpanStyle(color = HakuColors.Violet, fontWeight = FontWeight.SemiBold), 0, end) }, OffsetMapping.Identity)
}

private val LEADING_MENTION = Regex("""^\s*[@/]\S+""")

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
private fun InputPill(text: TextFieldValue, onText: (TextFieldValue) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().hakuShadow(HakuRadius.Pill).background(HakuColors.Surface, HakuRadius.Pill).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.weight(1f)) {
      if (text.text.isEmpty()) Text("พิมพ์คำถาม…", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
      BasicTextField(
        text,
        onText,
        Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = HakuColors.Ink),
        cursorBrush = SolidColor(HakuColors.Violet),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        maxLines = 3,
        visualTransformation = MentionHighlight,
      )
    }
    Box(
      Modifier.padding(start = 8.dp).size(44.dp).clip(CircleShape).background(HakuColors.Ink).clickable(role = Role.Button, onClickLabel = "ส่ง", onClick = onSend),
      contentAlignment = Alignment.Center,
    ) { Icon(HakuIcons.Send, "ส่ง", tint = HakuColors.Surface, modifier = Modifier.size(20.dp)) }
  }
}
