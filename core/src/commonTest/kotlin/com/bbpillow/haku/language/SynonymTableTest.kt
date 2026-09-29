package com.bbpillow.haku.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynonymTableTest {
  @Test
  fun expandsToWholeGroupAndUnknownTermsToThemselves() {
    val table = SynonymTable(listOf(listOf("a", "b"), listOf("b", "c")))
    assertEquals(setOf("a", "b", "c"), table.expand("b"))
    assertEquals(setOf("a", "b"), table.expand("a"))
    assertEquals(setOf("zzz"), table.expand("zzz"))
  }

  @Test
  fun thaiDraftTableIsSymmetricAndBigEnough() {
    assertTrue(thaiSynonyms.terms.size >= 200)
    for (t in thaiSynonyms.terms) for (s in thaiSynonyms.expand(t)) assertTrue(t in thaiSynonyms.expand(s), "$t <-> $s")
    assertTrue("ทาน" in thaiSynonyms.expand("กิน"))
  }
}
