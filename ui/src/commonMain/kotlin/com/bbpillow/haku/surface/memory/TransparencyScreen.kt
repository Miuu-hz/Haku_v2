package com.bbpillow.haku.surface.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.bbpillow.haku.surface.components.SectionLabel
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.theme.HakuRadius
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.ConfirmDialog
import com.bbpillow.haku.surface.components.EgressCounter
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.MemoryRow
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionHeader
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.components.badge
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuTone

/**
 * Screen 4 of haku-mockup.html (assets E1/E2/E3): egress counter, every remembered fact by category with its provenance,
 * forget per fact, the connection report, and where the data lives. State-hoisted: the caller owns [state]
 * (from TransparencyPresenter).
 */
@Composable
fun TransparencyScreen(
  state: TransparencyUiState,
  onForget: (MemoryFact) -> Unit,
  onForgetCategory: (String) -> Unit = {},
  onForgetEverything: () -> Unit = {},
  /** Ask the OS for a place to save the export (Android: ACTION_CREATE_DOCUMENT); the user picks it, so it is not egress. */
  onExport: () -> Unit = {},
  /** P9.4: the Haku Plus screen. */
  onOpenPlus: () -> Unit = {},
  /** Mockup screen 6: the home node status (P7, coming soon). */
  onOpenHomeNode: () -> Unit = {},
  /** Opens the Settings screen ("ตั้งค่า") from the gear button in the header. */
  onOpenSettings: () -> Unit = {},
  /** P5.3 the standing-intentions list ("ความตั้งใจค้างไว้"). */
  onOpenIntentions: () -> Unit = {},
  /** Small action at the top-right of the title area that scrolls with the list (debug builds: the demo-data chip). */
  headerAction: (@Composable () -> Unit)? = null,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var forgetting by remember { mutableStateOf<MemoryFact?>(null) }
  var forgettingCategory by remember { mutableStateOf<MemoryCategoryItem?>(null) }
  var forgettingAll by remember { mutableStateOf(false) }
  var typed by remember { mutableStateOf("") }

  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Box(Modifier.fillMaxWidth()) {
        ScreenTitle("สิ่งที่ Haku จำ", eyebrow = "เปิดดูและลบได้ทุกบรรทัด")
        Row(
          Modifier.align(Alignment.TopEnd),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          headerAction?.invoke()
          Box(
            Modifier
              .size(36.dp)
              .clip(HakuRadius.Pill)
              .clickable(role = Role.Button, onClickLabel = "ตั้งค่า", onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
          ) {
            Icon(HakuIcons.Settings, contentDescription = "ตั้งค่า", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
          }
        }
      }
    }
    item { EgressCounter(state.egressToday) }
    items(state.categories, key = { it.slug }) { c ->
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryHeader(c, onForgetCategory = { forgettingCategory = c })
        SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
          if (c.facts.isEmpty()) {
            Text("ยังไม่มีข้อมูล", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(vertical = 12.dp))
          }
          c.facts.forEachIndexed { i, f ->
            MemoryRow(f.text, f.date, f.provenance.badge(), onForget = { forgetting = f }, divider = i < c.facts.lastIndex)
          }
        }
      }
    }
    if (state.dreamReports.isNotEmpty()) {
      item { SectionHeader("รายงานการย่อยความจำ", Modifier.padding(top = 8.dp), trailing = "${state.dreamReports.size} คืนล่าสุด") }
      items(state.dreamReports, key = { it.day }) { r ->
        SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
          Text(r.title, style = MaterialTheme.typography.titleSmall)
          Text(r.text, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 6.dp))
        }
      }
    }
    item { SectionHeader("การเชื่อมต่อ", Modifier.padding(top = 8.dp), trailing = "30 วันล่าสุด") }
    item { ConnectionsCard(state) }
    item { WhereDataLivesCard(Modifier.padding(top = 8.dp)) }
    item { SectionLabel("ตั้งค่าและข้อมูล", Modifier.padding(top = 8.dp)) }
    item {
      SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
        ActionRow("ความตั้งใจค้างไว้", onOpenIntentions)
        ActionRow("เครื่องที่บ้าน", onOpenHomeNode, chip = "เร็ว ๆ นี้")
        ActionRow("Haku Plus", onOpenPlus)
        ActionRow("ส่งออกความจำทั้งหมดเป็นไฟล์", onExport)
        ActionRow("ลืมทุกอย่าง", { typed = ""; forgettingAll = true }, danger = true, divider = false)
      }
    }
  }

  forgetting?.let { fact ->
    ConfirmDialog(
      title = "ลืมข้อนี้?",
      message = "\"${fact.text}\" จะถูกลบออกจากความจำในเครื่องทันที และเรียกคืนไม่ได้",
      confirmText = "ลืม",
      onConfirm = { onForget(fact); forgetting = null },
      onDismiss = { forgetting = null },
    )
  }

  forgettingCategory?.let { c ->
    ConfirmDialog(
      title = "ลืมทั้งหมวด \"${c.title}\"?",
      message = "ความจำ ${c.count} ข้อในหมวดนี้จะถูกลบออกจากเครื่องทันที และเรียกคืนไม่ได้",
      confirmText = "ลืมทั้งหมวด",
      onConfirm = { onForgetCategory(c.slug); forgettingCategory = null },
      onDismiss = { forgettingCategory = null },
    )
  }

  if (forgettingAll) {
    ConfirmDialog(
      title = "ลืมทุกอย่าง?",
      message = "ความจำ บันทึกประจำวัน สถานที่ สรุปรายวัน ประวัติแชท และข้อมูลที่จับได้ทั้งหมดจะถูกลบจากเครื่องนี้ และเรียกคืนไม่ได้ " +
        "(การตั้งค่าที่เลือกตอนเริ่มใช้ยังอยู่) พิมพ์ \"$FORGET_PHRASE\" เพื่อยืนยัน",
      confirmText = "ลบทุกอย่าง",
      onConfirm = { if (typed.trim() == FORGET_PHRASE) { onForgetEverything(); forgettingAll = false } },
      onDismiss = { forgettingAll = false },
    ) {
      OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
  }
}

/** .sec-lbl group label with its count and a quiet overflow holding "forget this whole category". */
@Composable
private fun CategoryHeader(c: MemoryCategoryItem, onForgetCategory: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      SectionLabel(c.title)
      Text(c.description, style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
    Text("${c.count} ข้อ", style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted)
    if (c.count > 0) {
      Box {
        Box(
          Modifier.size(36.dp).clip(HakuRadius.Pill).clickable(role = Role.Button, onClickLabel = "ตัวเลือกหมวด ${c.title}") { menu = true },
          contentAlignment = Alignment.Center,
        ) { Icon(HakuIcons.More, "ตัวเลือกเพิ่มเติม", tint = HakuColors.Muted, modifier = Modifier.size(20.dp)) }
        DropdownMenu(menu, { menu = false }, containerColor = HakuColors.Surface, shape = HakuRadius.Small) {
          DropdownMenuItem(
            text = { Text("ลืมทั้งหมวดนี้", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Coral) },
            onClick = { menu = false; onForgetCategory() },
          )
        }
      }
    } else {
      Spacer(Modifier.width(12.dp))
    }
  }
}

/** A settings row inside one card: label, optional chip, chevron; [danger] = coral label. */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit, chip: String? = null, danger: Boolean = false, divider: Boolean = true) {
  Column {
    Row(
      Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = if (danger) HakuColors.Coral else HakuColors.Ink, modifier = Modifier.weight(1f))
      chip?.let { StatChip(it, Modifier.padding(end = 6.dp)) }
      Icon(HakuIcons.Chevron, null, tint = HakuColors.Muted, modifier = Modifier.size(18.dp))
    }
    if (divider) HorizontalDivider(color = HakuColors.Line)
  }
}

/** E3: every connection Haku opened, the total line first. */
@Composable
private fun ConnectionsCard(state: TransparencyUiState) {
  SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
    Text(state.connectionsTotal, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
    if (state.connections.isEmpty()) {
      Text("ยังไม่มีการเชื่อมต่อออกนอกเครื่อง", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 8.dp))
    }
    state.connections.forEach { c ->
      HorizontalDivider(color = HakuColors.Line, modifier = Modifier.padding(vertical = 10.dp))
      Text(c.purpose, style = MaterialTheme.typography.bodyMedium)
      Text(
        listOfNotNull(c.time, c.host, c.received?.let { "รับเข้า $it" }, c.result).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = HakuColors.Muted,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
    Text(
      "การซื้อ Haku Plus ทำผ่านแอป Google Play ซึ่งเชื่อมต่อแทน Haku จึงไม่แสดงที่นี่",
      style = MaterialTheme.typography.labelSmall,
      color = HakuColors.Muted,
      modifier = Modifier.padding(top = 10.dp),
    )
  }
}

/** The words the user must type before "forget everything" runs. */
const val FORGET_PHRASE = "ลืมทุกอย่าง"

@Composable
private fun WhereDataLivesCard(modifier: Modifier = Modifier) {
  PastelBoard(HakuColors.Mint, modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      IconTile(HakuIcons.Lock, HakuTone.Mint)
      Text("ข้อมูลของคุณอยู่ที่ไหน", style = MaterialTheme.typography.titleSmall)
    }
    Text(
      "ทุกอย่างที่ Haku จำเก็บเป็นไฟล์ในเครื่องนี้เท่านั้น ไม่มีบัญชีออนไลน์ ไม่สำรองขึ้นคลาวด์ " +
        "และการคิดทำด้วยโมเดลในเครื่อง ถ้ามีข้อมูลออกนอกเครื่องเมื่อไร ตัวเลขด้านบนจะนับให้เห็น",
      style = MaterialTheme.typography.bodySmall,
      color = HakuColors.Muted,
      modifier = Modifier.padding(top = 12.dp),
    )
  }
}
