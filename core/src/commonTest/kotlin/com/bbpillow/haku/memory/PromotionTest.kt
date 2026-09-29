package com.bbpillow.haku.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** P1.7: EXTERNAL/SYSTEM can never reach curated core or standing instructions, however often it is tried. */
class PromotionTest {
  private val fs = FakeFileSystem()
  private var n = 0
  private val store = MemoryStore(fs, "/m".toPath()) { "id${++n}" }
  private val t = 1_790_000_000_000L
  private val day = MemoryFormat.dayOf(t)

  private fun daily(p: Provenance) = store.appendDaily(DailyEventInput("ข้อความ ${p.key}", p, t))
  private fun ref(id: String) = MemoryRef(MemoryLevel.DAILY, day, id)

  @Test fun `user record promotes to core and keeps its own provenance`() {
    val src = daily(Provenance.INFERRED_FROM_USER_DATA)
    val out = store.promote(ref(src.id), PromotionTarget.Core("you"))!!
    assertEquals(src.text, out.text)
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, out.provenance)
    assertEquals(listOf(out), store.readCategory("you"))
  }

  @Test fun `user record promotes to standing`() {
    val src = daily(Provenance.USER_TYPED)
    store.promote(ref(src.id), PromotionTarget.Standing)!!
    assertEquals(listOf(src.text), store.readStanding().map { it.text })
  }

  @Test fun `external and system records cannot be promoted, repeatedly, to either target`() {
    for (p in listOf(Provenance.EXTERNAL, Provenance.SYSTEM)) {
      val src = daily(p)
      repeat(5) {
        assertFailsWith<ProvenanceNotAllowedException> { store.promote(ref(src.id), PromotionTarget.Core("you")) }
        assertFailsWith<ProvenanceNotAllowedException> { store.promote(ref(src.id), PromotionTarget.Standing) }
      }
    }
    assertTrue(store.readCategory("you").isEmpty())
    assertTrue(store.readStanding().isEmpty())
  }

  @Test fun `direct long-term writes reject external and system too`() {
    for (p in listOf(Provenance.EXTERNAL, Provenance.SYSTEM)) {
      assertFailsWith<ProvenanceNotAllowedException> { store.writeCoreFact(CoreFactInput("you", "x", p, t)) }
      assertFailsWith<ProvenanceNotAllowedException> { store.addStanding(NoteInput("x", p, t)) }
    }
  }

  @Test fun `missing source returns null and only daily or intention can be a source`() {
    assertNull(store.promote(ref("nope"), PromotionTarget.Standing))
    assertFailsWith<IllegalArgumentException> {
      store.promote(MemoryRef(MemoryLevel.CORE, "you", "x"), PromotionTarget.Standing)
    }
  }

  @Test fun `find and listDays address records`() {
    val a = daily(Provenance.USER_TYPED)
    assertEquals(a, store.find(ref(a.id)))
    assertNull(store.find(ref("zzz")))
    assertEquals(listOf(day), store.listDays())
  }
}
