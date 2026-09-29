package com.bbpillow.haku.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {
  @Test fun `empty text costs nothing`() = assertEquals(0, TokenEstimator.estimate(""))

  @Test fun `thai costs more per character than english`() {
    val thai = TokenEstimator.estimate("ไปกินข้าวกับแม่")
    val latin = TokenEstimator.estimate("went to eat")
    assertTrue(thai > latin, "thai=$thai latin=$latin")
  }

  @Test fun `exact values follow the weights and round up`() {
    assertEquals(3, TokenEstimator.estimate("ไปกิน")) // 5 thai chars * 0.6 = 3.0
    assertEquals(1, TokenEstimator.estimate("abc")) // 0.75 rounds up
  }

  @Test fun `emoji counts once per code point not per surrogate`() = assertEquals(1, TokenEstimator.estimate("😀"))

  @Test fun `estimate never shrinks when text grows`() {
    var prev = 0
    for (n in 0..50) {
      val t = TokenEstimator.estimate("ก".repeat(n) + "a".repeat(n))
      assertTrue(t >= prev)
      prev = t
    }
  }
}
