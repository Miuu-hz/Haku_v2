package com.bbpillow.haku.capture

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureEventStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun memory(): CaptureEventStore = CaptureEventStore(BundledSQLiteDriver().open(":memory:").also(opened::add))

  private val all = { from: Long, to: Long -> CaptureQueryInput(from, to) }

  @Test fun everySourceTypeRoundTrips() {
    val store = memory()
    val place = EventPlace(13.75, 100.5, "p1")
    val events = listOf(
      CaptureEvent("a", 1, CaptureSource.DwellStart, place),
      CaptureEvent("b", 2, CaptureSource.DwellEnd, place, confidence = 0.5),
      CaptureEvent("c", 3, CaptureSource.ActivityTransition(ActivityKind.IN_VEHICLE, true)),
      CaptureEvent("d", 4, CaptureSource.ActivityTransition(ActivityKind.STILL, false)),
      CaptureEvent("e", 5, CaptureSource.DeviceSignal(DeviceSignalKind.CHARGING_START)),
      CaptureEvent("f", 6, CaptureSource.CalendarEntry("ประชุม \"ทีม\" ครั้งที่ 1' 🎉; DROP TABLE events"), endUtcMs = 99),
    )
    assertEquals(6, store.appendAll(events))
    assertEquals(events, store.query(all(0, 100)))
  }

  @Test fun rangeIsHalfOpenAndOrdered() {
    val store = memory()
    store.appendAll(listOf(3L, 1L, 2L).map { CaptureEvent("e$it", it * 10, CaptureSource.DeviceSignal(DeviceSignalKind.UNLOCK)) })
    assertEquals(listOf("e1", "e2"), store.query(all(10, 30)).map { it.id })
    assertTrue(store.query(all(31, 40)).isEmpty())
    assertTrue(store.query(all(10, 10)).isEmpty())
  }

  @Test fun typeFilter() {
    val store = memory()
    store.appendAll(
      listOf(
        CaptureEvent("a", 1, CaptureSource.DwellStart, EventPlace(1.0, 1.0)),
        CaptureEvent("b", 2, CaptureSource.CalendarEntry("x")),
      ),
    )
    assertEquals(listOf("b"), store.query(CaptureQueryInput(0, 9, setOf(CaptureTypes.CALENDAR))).map { it.id })
    assertEquals(2, store.query(CaptureQueryInput(0, 9, setOf(CaptureTypes.CALENDAR, CaptureTypes.DWELL_START))).size)
    assertTrue(store.query(CaptureQueryInput(0, 9, emptySet())).isEmpty())
  }

  @Test fun duplicateIdIsIgnored() {
    val store = memory()
    val e = CaptureEvent("a", 1, CaptureSource.DwellEnd)
    assertTrue(store.append(e))
    assertFalse(store.append(e.copy(timeUtcMs = 2)))
    assertEquals(1, store.count())
    assertEquals(1L, store.query(all(0, 9)).single().timeUtcMs)
  }

  @Test fun deletedEventNeverComesBack() {
    val store = memory()
    val e = CaptureEvent("cal-1", 5, CaptureSource.CalendarEntry("หมอฟัน"))
    store.append(e)
    assertTrue(store.delete("cal-1"))
    assertEquals(0, store.count())
    assertFalse(store.append(e), "producer re-reads the calendar")
    assertEquals(0, store.appendAll(listOf(e)))
    assertEquals(0, store.count())
  }

  @Test fun deleteOfUnknownIdReturnsFalseButStillBlocksIt() {
    val store = memory()
    assertFalse(store.delete("ghost"))
    assertFalse(store.append(CaptureEvent("ghost", 1, CaptureSource.DwellEnd)))
  }

  @Test fun tombstoneSurvivesReopeningTheDatabase() {
    val file = File.createTempFile("capture", ".db").also { it.deleteOnExit() }
    val first = BundledSQLiteDriver().open(file.path)
    CaptureEventStore(first).also {
      it.append(CaptureEvent("x", 1, CaptureSource.DwellEnd))
      it.delete("x")
    }
    first.close()
    val second = BundledSQLiteDriver().open(file.path).also(opened::add)
    assertFalse(CaptureEventStore(second).append(CaptureEvent("x", 1, CaptureSource.DwellEnd)))
  }

  @Test fun retentionPurgeRemovesOldRowsWithoutTombstones() {
    val store = memory()
    store.appendAll((1L..5L).map { CaptureEvent("e$it", it, CaptureSource.DeviceSignal(DeviceSignalKind.UNLOCK)) })
    assertEquals(2, store.purgeBefore(3))
    assertEquals(listOf("e3", "e4", "e5"), store.query(all(0, 99)).map { it.id })
    assertTrue(store.append(CaptureEvent("e1", 1, CaptureSource.DeviceSignal(DeviceSignalKind.UNLOCK))))
  }

  /** P2.1 pass criterion: 100k events, range query under 30 ms. */
  @Test fun rangeQueryOver100kEventsIsUnder30ms() {
    val store = memory()
    val day = 86_400_000L
    val events = (0 until 100_000).map { i ->
      CaptureEvent("e$i", i * (100 * day / 100_000), CaptureSource.DeviceSignal(DeviceSignalKind.UNLOCK), EventPlace(13.7, 100.5))
    }
    assertEquals(100_000, store.appendAll(events))
    val one = CaptureQueryInput(40 * day, 41 * day)
    store.query(one) // warm-up: JIT and statement cache
    val runs = (1..5).map {
      val t0 = System.nanoTime()
      val rows = store.query(one)
      assertEquals(1000, rows.size)
      (System.nanoTime() - t0) / 1_000_000.0
    }
    println("range query over 100k events (1 day = 1000 rows), ms per run: $runs")
    assertTrue(runs.min() < 30.0, "fastest run was ${runs.min()} ms")
    assertTrue(runs.max() < 30.0, "slowest run was ${runs.max()} ms")
  }
}
