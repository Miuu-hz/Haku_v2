package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import kotlinx.coroutines.CancellationException

@Composable
fun LicensesScreen(
  onBack: () -> Unit,
  /** Reads a file under assets/licenses; null when it cannot be read. */
  loadText: suspend (asset: String) -> String?,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var selectedNotice by remember { mutableStateOf<OpenSourceNotice?>(null) }

  val notice = selectedNotice
  if (notice != null) {
    var text by remember(notice) { mutableStateOf<String?>(null) }
    var loading by remember(notice) { mutableStateOf(true) }

    LaunchedEffect(notice) {
      val asset = notice.textAsset
      text = if (asset != null) {
        try {
          loadText(asset)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          null
        }
      } else {
        null
      }
      loading = false
    }

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
          .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = { selectedNotice = null }),
        contentAlignment = Alignment.Center,
      ) {
        Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
      }

      ScreenTitle(notice.license, eyebrow = notice.name)

      SurfaceCard(Modifier.fillMaxWidth()) {
        when {
          loading -> Text("กำลังเปิด…", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
          text != null -> Text(text!!, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
          else -> Text("เปิดไฟล์ไม่ได้", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
        }
      }
    }
  } else {
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

      ScreenTitle("สัญญาอนุญาตโอเพนซอร์ส")

      OpenSourceNotices.forEach { item ->
        val hasAsset = item.textAsset != null
        val cardModifier = if (hasAsset) {
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(HakuRadius.Card)
            .clickable(role = Role.Button, onClickLabel = "อ่านสัญญาอนุญาต") {
              selectedNotice = item
            }
        } else {
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
        }
        SurfaceCard(cardModifier) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
            Text(item.what, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
            Text(item.license, style = MaterialTheme.typography.bodySmall, color = HakuColors.Ink)
            if (hasAsset) {
              Text(
                "แตะเพื่ออ่านข้อความเต็ม",
                style = MaterialTheme.typography.labelSmall,
                color = HakuColors.Muted,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
          }
        }
      }
    }
  }
}
