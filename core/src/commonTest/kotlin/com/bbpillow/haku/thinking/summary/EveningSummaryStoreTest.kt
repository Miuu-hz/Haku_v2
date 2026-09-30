package com.bbpillow.haku.thinking.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class EveningSummaryStoreTest {
  private val fs = FakeFileSystem()
  private val store = EveningSummaryStore(fs, "/s".toPath())
  private fun rec(day: String) = EveningSummaryRecord(day, "เช้าไปออฟฟิศ", SummarySource.MODEL)

  @Test fun deleteRemovesOnlyThatDayAndIsIdempotent() {
    store.save(rec("2026-09-08"))
    store.save(rec("2026-09-09"))
    assertTrue(store.delete("2026-09-08"))
    assertNull(store.find("2026-09-08"))
    assertEquals(rec("2026-09-09"), store.find("2026-09-09"))
    assertFalse(store.delete("2026-09-08"))
  }

  @Test fun clearRemovesEverythingAndStoreStillWorksAfter() {
    store.save(rec("2026-09-08"))
    store.clear()
    assertNull(store.find("2026-09-08"))
    store.save(rec("2026-09-10"))
    assertEquals(rec("2026-09-10"), store.find("2026-09-10"))
  }

  @Test fun deleteRejectsBadDay() {
    assertFailsWith<IllegalArgumentException> { store.delete("../x") }
  }
}
