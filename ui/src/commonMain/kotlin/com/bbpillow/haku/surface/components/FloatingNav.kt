package com.bbpillow.haku.surface.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius

/** One tab in the floating nav; label is for accessibility only. */
data class NavItem(val icon: ImageVector, val label: String)

/** .tabbar: floating white capsule, active tab as a black pill. */
@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .hakuShadow(HakuRadius.Pill)
      .background(HakuColors.Surface.copy(alpha = 0.92f), HakuRadius.Pill)
      .padding(9.dp),
    horizontalArrangement = Arrangement.SpaceAround,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    items.forEachIndexed { i, item ->
      val on = i == selected
      Box(
        Modifier
          .size(width = if (on) 76.dp else 56.dp, height = 46.dp)
          .clip(HakuRadius.Pill)
          .background(if (on) HakuColors.Ink else HakuColors.Surface.copy(alpha = 0f))
          .clickable(role = Role.Tab, onClickLabel = item.label) { onSelect(i) },
        contentAlignment = Alignment.Center,
      ) {
        Icon(item.icon, item.label, tint = if (on) HakuColors.Surface else HakuColors.Muted, modifier = Modifier.size(22.dp))
      }
    }
  }
}
