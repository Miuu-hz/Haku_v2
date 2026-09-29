package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class TransparencyPresenterTest {
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "f${++n}" }
  private val presenter = TransparencyPresenter(memory, 420)
  private val sep23 = 1_790_121_600_000L // 2026-09-23 00:00 UTC

  @Test fun categoriesAreThaiWithCountsAndProvenance() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "ชอบกาแฟไม่หวาน", Provenance.USER_TYPED, sep23))
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "เข้างานราว 08:45", Provenance.INFERRED_FROM_USER_DATA, sep23))
    val state = presenter.load()
    assertEquals(listOf("ตัวคุณ", "เรื่องที่สนใจ", "ด้านของชีวิต", "โครงการ"), state.categories.map { it.title })
    val you = state.categories.first()
    assertEquals(2, you.count)
    assertEquals(MemoryFact(CoreCategories.YOU, "f1", "ชอบกาแฟไม่หวาน", "23 ก.ย.", Provenance.USER_TYPED), you.facts[0])
    assertEquals(0, state.egressToday)
  }

  @Test fun forgetRemovesTheFactForGood() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.TOPICS, "สุขภาพ", Provenance.USER_TYPED, sep23))
    val fact = presenter.load().categories[1].facts.single()
    val after = presenter.forget(fact)
    assertEquals(0, after.categories[1].count)
    assertTrue(TransparencyPresenter(memory, 420).load().categories.all { it.facts.isEmpty() })
  }

  @Test fun unknownCategoryFallsBackToItsSlugAndDescription() {
    memory.writeCoreFact(CoreFactInput("move-condo", "ย้ายคอนโดเดือนหน้า", Provenance.USER_TYPED, sep23))
    val c = presenter.load().categories.last()
    assertEquals("move-condo", c.title)
    assertEquals(1, c.count)
  }
}
