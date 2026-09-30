package com.bbpillow.haku.capture

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureRecorderTest {
  private val db = BundledSQLiteDriver().open(":memory:")
  private val store = CaptureEventStore(db)
  private val recorder = CaptureRecorder(store)
  private val home = EventPlace(13.7563, 100.5018)
  private val office = EventPlace(13.7300, 100.5600)
  private val hour = 3_600_000L
  private val t0 = 1_760_000_000_000L

  @AfterTest fun close() = db.close()

  private fun all() = store.query(CaptureQueryInput(0, Long.MAX_VALUE))

  @Test fun aDayOfWakeUpsBecomesPairedDwells() {
    recorder.onWake(listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true)), home, t0)
    recorder.onWake(listOf(WakeTrigger.Activity(t0 + 9 * hour, ActivityKind.IN_VEHICLE, true)), office, t0 + 9 * hour)
    recorder.onWake(listOf(WakeTrigger.Activity(t0 + 10 * hour, ActivityKind.STILL, true)), office, t0 + 10 * hour)
    val dwells = PlaceRecognizer.pairDwells(all())
    assertEquals(listOf(DwellRecord(t0, t0 + 9 * hour, home.lat, home.lon)), dwells)
    assertEquals(office, recorder.openDwell(t0 + 11 * hour)?.place)
  }

  @Test fun openDwellComesFromTheStoreSoAProcessRestartLosesNothing() {
    recorder.onWake(listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true)), home, t0)
    val restarted = CaptureRecorder(CaptureEventStore(db))
    assertEquals(home, restarted.openDwell(t0 + hour)?.place)
    assertNull(restarted.openDwell(t0 + CaptureRecorder.LOOKBACK_MS + 1))
  }

  @Test fun retriedWakeUpIsStoredOnce() {
    val wake = listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true))
    recorder.onWake(wake, home, t0)
    val countAfterFirst = store.count()
    // Same broadcast again: the stay is open and near, so only the (duplicate) activity event is produced.
    recorder.onWake(wake, home, t0)
    assertEquals(countAfterFirst, store.count())
  }

  @Test fun deletedDwellStaysDeleted() {
    recorder.onWake(listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true)), home, t0)
    store.delete(CaptureIds.dwellStart(t0))
    store.delete(CaptureIds.activity(ActivityKind.STILL, true, t0))
    recorder.onWake(listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true)), home, t0)
    assertTrue(all().isEmpty())
  }

  @Test fun chargingEdgesAreNotDoubleCounted() {
    assertFalse(recorder.recordCharging(false, t0)) // nothing known yet, not charging = no news
    assertTrue(recorder.recordCharging(true, t0 + 1000))
    assertFalse(recorder.recordCharging(true, t0 + 2000, confidence = 0.5)) // catch-up check agrees
    assertTrue(recorder.recordCharging(false, t0 + 3000, confidence = 0.5))
    assertEquals(
      listOf(DeviceSignalKind.CHARGING_START, DeviceSignalKind.CHARGING_END),
      all().map { (it.source as CaptureSource.DeviceSignal).signal },
    )
    assertEquals(0.5, all().last().confidence)
  }

  @Test fun unlockIsRecordedWithAStableId() {
    assertTrue(recorder.recordSignal(DeviceSignalKind.UNLOCK, t0))
    assertFalse(recorder.recordSignal(DeviceSignalKind.UNLOCK, t0))
    assertEquals("device-unlock-$t0", all().single().id)
  }

  @Test fun calendarKeepsRealAppointmentsOnly() {
    val rows = listOf(
      CalendarRowInput(1, t0, t0 + hour, " ประชุมทีม "),
      CalendarRowInput(1, t0 + 24 * hour, t0 + 25 * hour, "ประชุมทีม"), // next recurrence: own id
      CalendarRowInput(2, t0, t0 + 24 * hour, "วันหยุด", allDay = true),
      CalendarRowInput(3, t0, t0 + hour, "ยกเลิก", canceled = true),
      CalendarRowInput(4, t0, t0 + hour, "ปฏิเสธ", declined = true),
      CalendarRowInput(5, t0, t0 + hour, "  "),
    )
    assertEquals(2, recorder.recordCalendar(rows))
    assertEquals(0, recorder.recordCalendar(rows))
    val stored = all()
    assertEquals(listOf("calendar-1-$t0", "calendar-1-${t0 + 24 * hour}"), stored.map { it.id })
    assertEquals(CaptureSource.CalendarEntry("ประชุมทีม"), stored.first().source)
    assertEquals(t0 + hour, stored.first().endUtcMs)
  }

  @Test fun rescheduledRetitledAndCancelledEventsReplaceTheStoredRows() {
    val from = t0 - hour
    val to = t0 + 48 * hour
    recorder.recordCalendar(listOf(CalendarRowInput(1, t0, t0 + hour, "หมอฟัน"), CalendarRowInput(2, t0, t0 + hour, "ประชุม")), from, to)
    // 1 moved two hours later, 2 cancelled, 3 new
    recorder.recordCalendar(listOf(CalendarRowInput(1, t0 + 2 * hour, t0 + 3 * hour, "หมอฟัน"), CalendarRowInput(3, t0, t0 + hour, "กาแฟ")), from, to)
    assertEquals(listOf("calendar-3-$t0", "calendar-1-${t0 + 2 * hour}"), all().map { it.id })
    // title edit on the same instance replaces the text
    assertEquals(1, recorder.recordCalendar(listOf(CalendarRowInput(3, t0, t0 + hour, "กาแฟกับแม่")), from, to))
    assertEquals(listOf(CaptureSource.CalendarEntry("กาแฟกับแม่")), all().filter { it.id == "calendar-3-$t0" }.map { it.source })
    // unchanged re-read: nothing new, and a user-deleted row stays deleted
    assertEquals(0, recorder.recordCalendar(listOf(CalendarRowInput(3, t0, t0 + hour, "กาแฟกับแม่")), from, to))
    store.delete("calendar-3-$t0")
    assertEquals(0, recorder.recordCalendar(listOf(CalendarRowInput(3, t0, t0 + hour, "กาแฟกับแม่")), from, to))
    assertTrue(all().none { it.id == "calendar-3-$t0" })
  }
}
