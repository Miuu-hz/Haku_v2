package com.bbpillow.haku.capture.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiaryLedgerStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun store() = DiaryLedgerStore(BundledSQLiteDriver().open(":memory:").also(opened::add))

  @Test fun writtenLineIsFoundByIdAndByRecord() {
    val s = store()
    s.recordWritten("dl-1", "2026-09-08", "r1")
    assertEquals(DiaryLedgerEntry("dl-1", "2026-09-08", "r1", false), s.find("dl-1"))
    assertEquals("dl-1", s.findByRecord("2026-09-08", "r1")?.lineId)
    assertNull(s.find("dl-2"))
  }

  @Test fun tombstoneSurvivesRewrite() {
    val s = store()
    s.recordWritten("dl-1", "2026-09-08", "r1")
    s.markDeleted("dl-1")
    s.recordWritten("dl-1", "2026-09-08", "r2")
    assertTrue(s.find("dl-1")!!.deleted)
  }

  @Test fun canTombstoneALineNeverSeen() {
    val s = store()
    s.markDeleted("dl-9")
    assertTrue(s.find("dl-9")!!.deleted)
  }
}
