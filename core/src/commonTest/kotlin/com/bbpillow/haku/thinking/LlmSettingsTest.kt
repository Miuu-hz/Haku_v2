package com.bbpillow.haku.thinking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmSettingsTest {
  private val fold5TotalMem = 11_412_520L * 1024 // /proc/meminfo on the 12 GB Fold5
  private val eightGbPhone = 7_400_000_000L
  private val sixGbPhone = 5_500_000_000L

  @Test fun twelveGbFold5GetsEightKAndLoadsOnAppOpenThoughItReportsUnderTwelve() {
    val s = LlmDefaults.forDevice(fold5TotalMem)
    assertEquals(8192, s.contextTokens)
    assertEquals(ModelLoadPolicy.ON_APP_OPEN, s.loadPolicy)
    assertFalse(s.speculativeDecoding)
  }

  @Test fun smallerPhonesGetSmallerContextAndLoadOnChatOpen() {
    assertEquals(4096, LlmDefaults.forDevice(eightGbPhone).contextTokens)
    assertEquals(ModelLoadPolicy.ON_CHAT_OPEN, LlmDefaults.forDevice(eightGbPhone).loadPolicy)
    assertEquals(2048, LlmDefaults.forDevice(sixGbPhone).contextTokens)
    assertEquals(ModelLoadPolicy.ON_CHAT_OPEN, LlmDefaults.forDevice(sixGbPhone).loadPolicy)
  }

  @Test fun stepDownWalksToTheSmallestThenStops() {
    assertEquals(16384, LlmDefaults.stepDown(32768))
    assertEquals(2048, LlmDefaults.stepDown(4096))
    assertNull(LlmDefaults.stepDown(2048))
  }

  @Test fun engineFieldsNeedAReloadButSamplerFieldsDoNot() {
    val s = LlmDefaults.forDevice(fold5TotalMem)
    assertTrue(s.needsReload(s.copy(contextTokens = 4096)))
    assertTrue(s.needsReload(s.copy(backend = ModelBackend.Cpu)))
    assertTrue(s.needsReload(s.copy(speculativeDecoding = true)))
    assertFalse(s.needsReload(s.copy(temperature = 0.5, topK = 10, topP = 0.5)))
    assertFalse(s.needsReload(s.copy(loadPolicy = ModelLoadPolicy.ON_ASK, idleReleaseSeconds = 300)))
  }

  @Test fun modelConfigCarriesEverySetting() {
    val s = LlmSettings(
      contextTokens = 16384,
      backend = ModelBackend.Cpu,
      speculativeDecoding = true,
      loadPolicy = ModelLoadPolicy.ON_ASK,
      temperature = 0.7,
      topK = 20,
      topP = 0.8,
    )
    assertEquals(
      ModelConfig("/m", ModelBackend.Cpu, 16384, 20, 0.8, 0.7, "/c", true),
      s.toModelConfig("/m", "/c"),
    )
  }
}
