package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.thinking.LlmDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSettingsPresenterTest {
  private val fold5 = 11_412_520L * 1024
  private val eightK = LlmDefaults.forDevice(fold5)

  private fun state(tokens: Int = 8192, metrics: LlmMetrics = LlmMetrics(), notice: String? = null) =
    LlmSettingsPresenter.state(eightK.copy(contextTokens = tokens), fold5, "gemma-4-E2B-it", metrics, notice)

  @Test fun marksTheSelectedStepAndRecommendsForThisDevice() {
    val s = state(tokens = 4096)
    assertEquals(listOf("2K", "4K", "8K", "16K", "32K"), s.contextOptions.map { it.label })
    assertEquals(listOf(4096), s.contextOptions.filter { it.selected }.map { it.tokens })
    assertEquals("แนะนำสำหรับเครื่องนี้: 8K", s.recommendedLabel)
  }

  @Test fun smallContextsSayAgentModeNeedsEightK() {
    assertTrue("8K" in state(tokens = 2048).contextNote)
    assertTrue("8K" in state(tokens = 4096).contextNote)
    assertFalse("ต้องการ 8K" in state(tokens = 8192).contextNote)
  }

  @Test fun unmeasuredThirtyTwoKStatesNoRamNumber() {
    assertFalse("GB" in state(tokens = 32768).contextNote)
  }

  @Test fun metricsShowADashUntilMeasuredThenRoundedValues() {
    assertEquals(listOf("–", "–", "–", "–"), state().metrics.map { it.second })
    val measured = state(metrics = LlmMetrics(loadMillis = 4902, firstTokenMillis = 382, charsPerSecond = 88.7, ramMb = 2816))
    assertEquals(listOf("4.9 วิ", "0.4 วิ", "88 ตัว/วิ", "2.8 GB"), measured.metrics.map { it.second })
  }

  @Test fun passesTheNoticeThrough() {
    assertEquals("ลดเหลือ 4K", state(notice = "ลดเหลือ 4K").notice)
  }
}
