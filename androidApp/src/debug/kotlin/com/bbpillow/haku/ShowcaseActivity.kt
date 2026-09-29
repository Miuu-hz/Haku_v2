package com.bbpillow.haku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bbpillow.haku.surface.showcase.ComponentShowcase
import com.bbpillow.haku.surface.theme.HakuTheme

/** Debug screen for eyeballing the UI kit against haku-mockup.html. */
class ShowcaseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { HakuTheme { ComponentShowcase() } }
  }
}
