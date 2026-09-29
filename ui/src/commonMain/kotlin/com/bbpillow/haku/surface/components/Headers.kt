package com.bbpillow.haku.surface.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors

/** .hello: One UI roomy title area, small grey eyebrow over a big headline. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, eyebrow: String? = null) {
  Column(modifier.padding(start = 6.dp, end = 6.dp, top = 32.dp, bottom = 10.dp)) {
    if (eyebrow != null) Text(eyebrow, style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted)
    Text(title, style = MaterialTheme.typography.headlineMedium, color = HakuColors.Ink)
  }
}

/** Row title with an optional grey trailing note ("ไฟล์ในเครื่องบ้าน · สื่อ + เอกสาร"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
  Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink, modifier = Modifier.weight(1f))
    if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
  }
}

/** .sec-lbl: small uppercase group label above a list card. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = HakuColors.Muted, modifier = modifier.padding(start = 4.dp))
}
