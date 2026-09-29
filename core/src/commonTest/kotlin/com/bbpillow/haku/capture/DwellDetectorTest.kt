package com.bbpillow.haku.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DwellDetectorTest {
  private val home = EventPlace(13.7563, 100.5018)
  private val homeNearby = EventPlace(13.7570, 100.5018) // ~80 m
  private val office = EventPlace(13.7300, 100.5600) // ~7 km

  private fun act(t: Long, kind: ActivityKind, entering: Boolean = true) = WakeTrigger.Activity(t, kind, entering)
  private fun start(t: Long, place: EventPlace) = CaptureEvent(CaptureIds.dwellStart(t), t, CaptureSource.DwellStart, place)
  private fun detect(fix: EventPlace?, open: CaptureEvent?, vararg t: WakeTrigger) = DwellDetector.detect(DwellDetectInput(t.toList(), fix, open))

  @Test fun stillWithFixOpensADwell() {
    val out = detect(home, null, act(1000, ActivityKind.STILL))
    assertEquals(listOf("activity-still-enter-1000", "dwell-start-1000"), out.events.map { it.id })
    assertEquals(home, out.openDwell?.place)
  }

  @Test fun stillWithoutFixRecordsOnlyTheActivity() {
    val out = detect(null, null, act(1000, ActivityKind.STILL))
    assertEquals(listOf("activity-still-enter-1000"), out.events.map { it.id })
    assertNull(out.openDwell)
  }

  @Test fun stillNearOpenDwellKeepsTheSameStay() {
    val open = start(1000, home)
    val out = detect(homeNearby, open, act(5000, ActivityKind.STILL))
    assertEquals(listOf("activity-still-enter-5000"), out.events.map { it.id })
    assertEquals(open, out.openDwell)
  }

  @Test fun stillFarFromOpenDwellClosesItAndOpensANewOne() {
    val out = detect(office, start(1000, home), act(5000, ActivityKind.STILL))
    assertEquals(listOf("activity-still-enter-5000", "dwell-end-5000", "dwell-start-5000"), out.events.map { it.id })
    assertEquals(home, out.events[1].place)
    assertEquals(office, out.openDwell?.place)
  }

  @Test fun walkingInsideTheSamePlaceDoesNotEndTheStay() {
    val open = start(1000, home)
    val out = detect(homeNearby, open, act(5000, ActivityKind.WALKING))
    assertEquals(open, out.openDwell)
    assertEquals(1, out.events.size)
  }

  @Test fun movingAwayEndsTheStay() {
    val out = detect(office, start(1000, home), act(5000, ActivityKind.IN_VEHICLE))
    assertEquals(listOf("activity-in_vehicle-enter-5000", "dwell-end-5000"), out.events.map { it.id })
    assertNull(out.openDwell)
  }

  @Test fun withoutAFixOnlyAVehicleEndsTheStay() {
    val open = start(1000, home)
    assertEquals(open, detect(null, open, act(5000, ActivityKind.WALKING)).openDwell)
    assertNull(detect(null, open, act(5000, ActivityKind.IN_VEHICLE)).openDwell)
  }

  @Test fun geofenceExitAlwaysEndsTheStay() {
    val out = detect(homeNearby, start(1000, home), WakeTrigger.GeofenceExit(5000))
    assertEquals(listOf("dwell-end-5000"), out.events.map { it.id })
    assertNull(out.openDwell)
  }

  @Test fun exitsAndStaleTriggersChangeNothing() {
    val open = start(5000, home)
    assertEquals(open, detect(office, open, act(6000, ActivityKind.STILL, entering = false)).openDwell)
    assertEquals(open, detect(office, open, act(4000, ActivityKind.IN_VEHICLE)).openDwell) // older than the stay
    assertNull(detect(office, null, WakeTrigger.GeofenceExit(6000)).openDwell)
  }

  @Test fun triggersAreAppliedInTimeOrder() {
    val out = detect(home, null, act(9000, ActivityKind.STILL), act(8000, ActivityKind.IN_VEHICLE, entering = false))
    assertEquals(listOf("activity-in_vehicle-exit-8000", "activity-still-enter-9000", "dwell-start-9000"), out.events.map { it.id })
  }

  @Test fun sameWakeUpTwiceGivesTheSameIds() {
    val a = detect(office, start(1000, home), act(5000, ActivityKind.STILL))
    val b = detect(office, start(1000, home), act(5000, ActivityKind.STILL))
    assertEquals(a, b)
  }

  @Test fun fixIsTakenOnlyWhenItMatters() {
    assertTrue(DwellDetector.needsFix(listOf(act(1, ActivityKind.STILL)), null))
    assertFalse(DwellDetector.needsFix(listOf(act(1, ActivityKind.WALKING)), null))
    assertTrue(DwellDetector.needsFix(listOf(act(1, ActivityKind.WALKING)), start(0, home)))
    assertFalse(DwellDetector.needsFix(listOf(act(1, ActivityKind.STILL, entering = false)), start(0, home)))
    assertFalse(DwellDetector.needsFix(listOf(WakeTrigger.GeofenceExit(1)), start(0, home)))
  }

  @Test fun wallTimeIsRoundedToTheSecond() {
    assertEquals(1_700_000_000_000, CaptureIds.wallTimeMs(1_700_000_005_400, 10_000, 4_900))
    assertEquals(1_700_000_001_000, CaptureIds.wallTimeMs(1_700_000_005_600, 10_000, 5_000))
    assertEquals(
      CaptureIds.wallTimeMs(1_700_000_005_000, 10_000, 4_000),
      CaptureIds.wallTimeMs(1_700_000_005_003, 10_001, 4_000),
    )
  }
}
