package com.bbpillow.haku.surface.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
 * Screen 4 of haku-mockup.html (asset E1/E2): egress counter, every remembered fact by category with its provenance,
 * forget per fact, and where the data lives. State-hoisted: the caller owns [state] (from TransparencyPresenter).
 */
// ponytail: forget-whole-category, the egress report (E3) and export (P3.4) come with their data sources.
@Composable
fun TransparencyScreen(
  state: TransparencyUiState,
  onForget: (MemoryFact) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var forgetting by remember { mutableStateOf<MemoryFact?>(null) }

  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { ScreenTitle("สิ่งที่ Haku จำ", eyebrow = "เปิดดูและลบได้ทุกบรรทัด") }
    item { EgressCounter(state.egressToday) }
    items(state.categories, key = { it.slug }) { c ->
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(c.title, Modifier.padding(top = 8.dp), trailing = "${c.count} ข้อ")
        Text(c.description, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted, modifier = Modifier.padding(start = 4.dp))
        SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
          if (c.facts.isEmpty()) {
            Text("ยังไม่มีข้อมูล", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted, modifier = Modifier.padding(vertical = 12.dp))
          }
          c.facts.forEachIndexed { i, f ->
            MemoryRow(f.text, f.date, f.provenance.badge(), onForget = { forgetting = f }, divider = i < c.facts.lastIndex)
          }
        }
      }
    }
    item { WhereDataLivesCard(Modifier.padding(top = 8.dp)) }
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
}

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
