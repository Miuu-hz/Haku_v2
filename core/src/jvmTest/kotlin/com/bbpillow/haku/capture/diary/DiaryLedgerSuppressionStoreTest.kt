package com.bbpillow.haku.capture.diary

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiaryLedgerSuppressionStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()
  private val file = File.createTempFile("ledger", ".db")

  @AfterTest fun close() {
    closeAll()
    file.delete()
  }

  private fun closeAll() {
    opened.forEach { it.close() }
    opened.clear()
  }

  private fun store() = DiaryLedgerStore(BundledSQLiteDriver().open(file.path).also(opened::add))

  private fun strike(s: DiaryLedgerStore, id: String, type: String) {
    s.recordWritten(id, "d", "r-$id", type)
    s.markDeleted(id)
  }

  @Test fun threeStrikesSuppressAndSurviveRestart() {
    val first = store()
    strike(first, "l1", "ARRIVE:p1")
    strike(first, "l2", "ARRIVE:p1")
    assertFalse(first.isSuppressed("ARRIVE:p1"))
    strike(first, "l3", "ARRIVE:p1")
    assertTrue(first.isSuppressed("ARRIVE:p1"))
    closeAll()

    val reopened = store()
    assertTrue(reopened.isSuppressed("ARRIVE:p1"))
    assertEquals(listOf(SuppressedLineType("ARRIVE:p1", 3)), reopened.suppressedTypes())
    assertFalse(reopened.isSuppressed("ARRIVE:p2"))
  }

  @Test fun repeatDeleteOfTheSameLineIsOneStrike() {
    val s = store()
    s.recordWritten("l1", "d", "r1", "T")
    repeat(5) { s.markDeleted("l1") }
    assertFalse(s.isSuppressed("T"))
  }

  @Test fun editStyleDeleteDoesNotCount() {
    val s = store()
    (1..3).forEach {
      s.recordWritten("l$it", "d", "r$it", "T")
      s.markDeleted("l$it", countsAsStrike = false)
    }
    assertFalse(s.isSuppressed("T"))
  }

  @Test fun restoreClearsTombstoneAndStrike() {
    val s = store()
    (1..3).forEach { strike(s, "l$it", "T") }
    assertTrue(s.isSuppressed("T"))
    assertTrue(s.restore("l3", "2026-09-03", "r9"))
    assertFalse(s.isSuppressed("T"))
    assertEquals(DiaryLedgerEntry("l3", "2026-09-03", "r9", false), s.find("l3"))
    assertFalse(s.restore("l3", "d", "r")) // not deleted any more
  }

  @Test fun unsuppressSurvivesRestartAndNeedsThreeMoreStrikes() {
    val s = store()
    (1..3).forEach { strike(s, "l$it", "T") }
    s.unsuppress("T")
    closeAll()

    val reopened = store()
    assertFalse(reopened.isSuppressed("T"))
    assertTrue(reopened.suppressedTypes().isEmpty())
    strike(reopened, "n1", "T")
    assertFalse(reopened.isSuppressed("T"))
  }

  @Test fun lineOfUnknownTypeNeverCounts() {
    val s = store()
    (1..4).forEach { s.markDeleted("never-seen-$it") }
    assertTrue(s.suppressedTypes().isEmpty())
  }

  @Test fun oldDatabaseWithoutTypeKeyColumnIsMigrated() {
    val old = BundledSQLiteDriver().open(file.path).also(opened::add)
    old.execSQL("CREATE TABLE diary_lines(id TEXT PRIMARY KEY, memory_day TEXT NOT NULL, record_id TEXT NOT NULL, deleted INTEGER NOT NULL)")
    old.execSQL("INSERT INTO diary_lines VALUES('old','d','r',1)")
    closeAll()

    val s = store()
    assertTrue(s.find("old")!!.deleted)
    strike(s, "new", "T")
    assertFalse(s.isSuppressed("T"))
  }
}
