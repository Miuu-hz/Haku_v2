package com.bbpillow.haku.thinking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextEmbedderTest {
  @Test fun cosineOfSameDirectionIsOneOppositeIsMinusOneOrthogonalIsZero() {
    assertEquals(1f, cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
    assertEquals(-1f, cosine(floatArrayOf(1f, 0f), floatArrayOf(-3f, 0f)), 1e-6f)
    assertEquals(0f, cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 5f)), 1e-6f)
  }

  @Test fun cosineWithZeroVectorIsZero() {
    assertEquals(0f, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
  }

  @Test fun cosineRejectsDifferentDims() {
    assertFailsWith<IllegalArgumentException> { cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
  }
}
