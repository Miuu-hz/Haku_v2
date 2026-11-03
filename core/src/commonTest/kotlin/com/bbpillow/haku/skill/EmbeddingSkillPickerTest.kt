package com.bbpillow.haku.skill

import com.bbpillow.haku.thinking.TextEmbedder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class EmbeddingSkillPickerTest {
  /** Bag of keywords: one axis per keyword, so cosine is predictable. */
  private class KeywordEmbedder(private val words: List<String>) : TextEmbedder {
    var calls = 0
    override val dims get() = words.size
    override suspend fun embed(texts: List<String>): List<FloatArray> {
      calls += texts.size
      return texts.map { t -> FloatArray(words.size) { i -> if (words[i] in t) 1f else 0f } }
    }
  }

  private fun skill(name: String, title: String, aliases: List<String> = emptyList()) =
    SkillRecord(name, title, "desc", "", emptyList(), "x.$name", aliases)

  private val appt = skill("add-appointment", "เพิ่มนัด", listOf("ปฏิทิน"))
  private val remind = skill("remind", "เตือน")
  private val emb = KeywordEmbedder(listOf("นัด", "ปฏิทิน", "เตือน", "อากาศ"))

  @Test fun picksClearWinnerAndCachesDocuments() = runTest {
    val p = EmbeddingSkillPicker(emb, anchors = emptyList())
    assertEquals(appt, p.pick("ลงปฏิทินว่ามีนัดพรุ่งนี้", listOf(appt, remind)))
    val before = emb.calls
    p.pick("มีนัดอีกอัน", listOf(appt, remind))
    assertEquals(before + 1, emb.calls) // only the query was embedded again
  }

  @Test fun aChatAnchorBeatsTheOnlySkill() = runTest {
    val e = KeywordEmbedder(listOf("นัด", "เมื่อวาน"))
    val p = EmbeddingSkillPicker(e, floor = 0f, anchors = listOf("เมื่อวาน"))
    assertNull(p.pick("เมื่อวานไปไหน", listOf(skill("a", "นัด"))))
    assertEquals("a", p.pick("เมื่อวานไปไหน นัด", listOf(skill("a", "นัด")))?.name)
    assertEquals("a", p.pick("ลงนัด", listOf(skill("a", "นัด")))?.name)
    val strict = EmbeddingSkillPicker(e, floor = 0f, chatMargin = 0.05f, anchors = listOf("เมื่อวาน"))
    assertNull(strict.pick("เมื่อวานไปไหน นัด", listOf(skill("a", "นัด"))))
  }

  @Test fun noSkillWhenBelowFloorOrTooClose() = runTest {
    val p = EmbeddingSkillPicker(emb, anchors = emptyList())
    assertNull(p.pick("อากาศวันนี้", listOf(appt, remind)))
    assertNull(p.pick("นัด เตือน", listOf(skill("a", "นัด"), skill("b", "เตือน"))))
    assertNull(p.pick("นัด", listOf(appt.copy(enabled = false))))
  }
}
