package com.bbpillow.haku.memory.index

import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.thinking.context.RecallRig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ชิ้น ค vectors: CRUD, model tagging, and every memory delete taking the vector with it (tombstone rule, PDPA). */
class VectorStoreTest {
  private val rig = RecallRig()
  private val v = rig.vectors

  @AfterTest fun close() = rig.close()

  private fun rec(day: String, id: String, vec: FloatArray, model: String = "m1") =
    VectorRecord(IndexedMemory(MemoryRef(MemoryLevel.DAILY, day, id), Provenance.USER_TYPED, 42L), model, vec)

  @Test fun `upsert, read back exactly, replace by key, remove`() {
    val a = floatArrayOf(0.25f, -1.5f, Float.MIN_VALUE, 3.4e38f)
    v.upsert(listOf(rec("2026-09-29", "a", a), rec("2026-09-29", "b", floatArrayOf(1f, 0f, 0f, 0f))))
    assertEquals(2, v.count("m1"))
    val back = v.all("m1").single { it.memory.ref.id == "a" }
    assertContentEquals(a, back.vector)
    assertEquals(Provenance.USER_TYPED, back.memory.provenance)
    assertEquals(42L, back.memory.atEpochMs)

    v.upsert(listOf(rec("2026-09-29", "a", floatArrayOf(9f, 9f, 9f, 9f))))
    assertEquals(2, v.count())
    assertContentEquals(floatArrayOf(9f, 9f, 9f, 9f), v.all("m1").single { it.memory.ref.id == "a" }.vector)

    assertTrue(v.remove(MemoryRef(MemoryLevel.DAILY, "2026-09-29", "a").key))
    assertFalse(v.remove(MemoryRef(MemoryLevel.DAILY, "2026-09-29", "a").key))
    assertEquals(setOf(MemoryRef(MemoryLevel.DAILY, "2026-09-29", "b").key), v.ids("m1"))
  }

  @Test fun `vectors are per model and other models can be dropped`() {
    v.upsert(listOf(rec("d", "a", floatArrayOf(1f), "old"), rec("d", "b", floatArrayOf(1f), "m1")))
    assertEquals(1, v.count("m1"))
    assertTrue(v.all("old").isNotEmpty())
    assertEquals(1, v.retainModel("m1"))
    assertEquals(0, v.count("old"))
    assertEquals(1, v.count())
  }

  @Test fun `deleting a line, a day, or everything deletes its vectors`() {
    val one = rig.daily("ไปวิ่งสวนลุมพินี", "2026-09-29T02:00:00.000Z")
    val two = rig.daily("ไปร้านกาแฟ", "2026-09-29T05:00:00.000Z")
    val other = rig.daily("ประชุมกับทีม", "2026-09-20T05:00:00.000Z")
    v.upsert(listOf(rec("2026-09-29", one.id, floatArrayOf(1f)), rec("2026-09-29", two.id, floatArrayOf(1f)), rec("2026-09-20", other.id, floatArrayOf(1f))))

    rig.memory.deleteDaily("2026-09-29", one.id)
    assertEquals(setOf(MemoryRef(MemoryLevel.DAILY, "2026-09-29", two.id).key, MemoryRef(MemoryLevel.DAILY, "2026-09-20", other.id).key), v.ids("m1"))

    rig.memory.deleteDay("2026-09-29")
    assertEquals(setOf(MemoryRef(MemoryLevel.DAILY, "2026-09-20", other.id).key), v.ids("m1"))

    rig.memory.deleteAll() // forget everything
    assertEquals(0, v.count())
  }
}
