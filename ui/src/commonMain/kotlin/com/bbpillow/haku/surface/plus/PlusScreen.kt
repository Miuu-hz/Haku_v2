package com.bbpillow.haku.surface.plus

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.HighlightCard
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.PastelBoard
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuTone

/** What Plus adds; [soon] = not built yet, said plainly. */
private val Benefits = listOf(
  "สถิติรายเดือนและรายปี พร้อมเทียบกับช่วงก่อน" to false,
  "สรุปการประชุมจากเสียง ทำในเครื่อง" to true,
  "เครื่องที่บ้าน (Home Node) ช่วยคิดงานใหญ่" to true,
)

/**
 * P9.4 Haku Plus. State-hoisted: [state] comes from PlayBilling; every price string is Play's own. The promise that
 * free memory is never locked or deleted sits above the prices on purpose.
 */
@Composable
fun PlusScreen(
  state: PlusUiState,
  onBuy: (productId: String) -> Unit,
  onRestore: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  LazyColumn(modifier.fillMaxWidth(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item { ScreenTitle("Haku Plus", eyebrow = "สนับสนุนให้ Haku ไปต่อ") }
    item {
      PastelBoard(HakuColors.Mint, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          IconTile(HakuIcons.Lock, HakuTone.Mint)
          Text("ความจำของคุณฟรีตลอด ไม่ถูกล็อกหรือลบ", style = MaterialTheme.typography.titleSmall)
        }
        Text(
          "ไดอารี ความจำ และสถิติสัปดาห์นี้ใช้ได้ตลอดโดยไม่ต้องจ่าย ถ้าเลิกสมัคร Plus จะเหลือแค่มุมมองแบบฟรี ข้อมูลไม่หายไปไหน",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
          modifier = Modifier.padding(top = 10.dp),
        )
      }
    }
    item {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Text("Plus เพิ่มอะไร", style = MaterialTheme.typography.titleSmall)
        Benefits.forEach { (text, soon) ->
          Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (soon) StatChip("เร็ว ๆ นี้")
          }
        }
      }
    }
    if (state.isPlus) {
      item {
        HighlightCard(Modifier.fillMaxWidth()) {
          Text("คุณเป็นสมาชิก Haku Plus แล้ว", style = MaterialTheme.typography.titleSmall)
          Text("ขอบคุณที่สนับสนุน เปลี่ยนหรือยกเลิกได้ที่การสมัครใช้บริการใน Google Play", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
    } else if (state.prices.isEmpty()) {
      item { Text("กำลังโหลดราคาจาก Google Play (ต้องต่ออินเทอร์เน็ต)", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted) }
    } else {
      items(state.prices, key = { it.productId }) { p ->
        SurfaceCard(Modifier.fillMaxWidth()) {
          Text(p.label, style = MaterialTheme.typography.titleSmall)
          Text(p.price, style = MaterialTheme.typography.titleLarge)
          p.founderNote?.let { StatChip(it, Modifier.padding(top = 6.dp), dark = true) }
          CapsuleButton("สมัคร${p.label}", { onBuy(p.productId) }, Modifier.padding(top = 12.dp))
        }
      }
    }
    state.message?.let { m -> item { Text(m, style = MaterialTheme.typography.bodyMedium) } }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CapsuleButton("กู้คืนการซื้อ", onRestore, outline = true)
        CapsuleButton("กลับ", onBack, outline = true)
        Text(
          "ชำระเงินผ่าน Google Play การซื้อผูกกับบัญชี Google ของคุณ Haku ไม่เห็นข้อมูลบัตรหรือการชำระเงิน",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
        )
      }
    }
  }
}
