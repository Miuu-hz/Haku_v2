package com.bbpillow.haku.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceRecognizerTest {
  private val min = SyntheticWeek.MIN_MS
  private val tz = SyntheticWeek.TZ_OFFSET_MIN

  private fun dwell(startMin: Long, endMin: Long, at: Pair<Double, Double> = SyntheticWeek.HOME) =
    DwellRecord(startMin * min, endMin * min, at.first, at.second)

  private fun near(p: PlaceRecord, at: Pair<Double, Double>) =
    PlaceRecognizer.distanceMeters(p.lat, p.lon, at.first, at.second) < 60

  private fun recognizeWeek(known: List<PlaceRecord> = emptyList()) = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), known, tz),
  )

  // --- P2.3 pass criteria on the synthetic week ---

  @Test fun weekYieldsExactlyFourPlaces() {
    val out = recognizeWeek()
    assertEquals(4, out.places.size)
    listOf(SyntheticWeek.HOME, SyntheticWeek.OFFICE, SyntheticWeek.CAFE, SyntheticWeek.GYM).forEach { spot ->
      assertEquals(1, out.places.count { near(it, spot) }, "one place near $spot")
    }
  }

  @Test fun guessesHomeAndWorkWithoutAnyHint() {
    val out = recognizeWeek()
    val home = out.guesses.single { it.category == PlaceCategory.HOME }
    val work = out.guesses.single { it.category == PlaceCategory.WORK }
    assertTrue(near(out.places.single { it.id == home.placeId }, SyntheticWeek.HOME))
    assertTrue(near(out.places.single { it.id == work.placeId }, SyntheticWeek.OFFICE))
    assertEquals(8, home.evidenceDays) // Sun night before the week + 7 nights
    assertEquals(5, work.evidenceDays)
    assertEquals(2, out.guesses.size)
  }

  @Test fun sevenMinuteStopIsNotAnEventButTwoHourCafeIs() {
    val out = recognizeWeek()
    assertTrue(out.places.none { near(it, SyntheticWeek.STORE) }, "store stop must vanish")
    val cafe = out.places.single { near(it, SyntheticWeek.CAFE) }
    val saturday = out.visits.filter { it.placeId == cafe.id && it.startUtcMs == SyntheticWeek.at(5, 10) }
    assertEquals(1, saturday.size)
  }

  @Test fun officeWalkOutIsMergedIntoOneMorningStay() {
    val out = recognizeWeek()
    val office = out.places.single { near(it, SyntheticWeek.OFFICE) }
    val monday = out.visits.filter { it.placeId == office.id && it.startUtcMs < SyntheticWeek.at(1, 0) }
    assertEquals(2, monday.size) // morning (merged across the 5 min walk) + afternoon
    assertEquals(SyntheticWeek.at(0, 12, 30), monday.first().endUtcMs)
  }

  @Test fun cafeAndGymAreNeverGuessedAsHomeOrWork() {
    val out = recognizeWeek()
    val guessed = out.guesses.map { it.placeId }.toSet()
    out.places.filter { near(it, SyntheticWeek.CAFE) || near(it, SyntheticWeek.GYM) }
      .forEach { assertTrue(it.id !in guessed) }
  }

  @Test fun confirmedPlacesAreReusedAndNotAskedAgain() {
    val first = recognizeWeek()
    val confirmed = first.places.map { p ->
      when (first.guesses.firstOrNull { it.placeId == p.id }?.category) {
        PlaceCategory.HOME -> p.copy(label = "บ้าน", category = PlaceCategory.HOME, confirmed = true)
        PlaceCategory.WORK -> p.copy(label = "ที่ทำงาน", category = PlaceCategory.WORK, confirmed = true)
        else -> p
      }
    }
    val second = recognizeWeek(confirmed)
    assertEquals(4, second.places.size)
    assertTrue(second.guesses.isEmpty(), "confirmed once, never re-asked")
    assertEquals(first.visits, second.visits)
    assertEquals("บ้าน", second.places.single { it.category == PlaceCategory.HOME }.label)
  }

  // --- pairing ---

  @Test fun pairDwellsSkipsOngoingStayAndEndsWithoutStart() {
    val p = EventPlace(1.0, 2.0)
    val events = listOf(
      CaptureEvent("e0", 5, CaptureSource.DwellEnd), // orphan end
      CaptureEvent("s1", 10, CaptureSource.DwellStart, p),
      CaptureEvent("e1", 20, CaptureSource.DwellEnd),
      CaptureEvent("s2", 30, CaptureSource.DwellStart, p), // still ongoing
      CaptureEvent("c", 15, CaptureSource.CalendarEntry("x")),
    )
    assertEquals(listOf(DwellRecord(10, 20, 1.0, 2.0)), PlaceRecognizer.pairDwells(events))
  }

  // --- merge boundaries ---

  @Test fun stopShorterThanFifteenMinutesDroppedExactlyFifteenKept() {
    assertTrue(PlaceRecognizer.mergeStops(listOf(dwell(0, 14))).isEmpty())
    assertEquals(1, PlaceRecognizer.mergeStops(listOf(dwell(0, 15))).size)
    assertTrue(PlaceRecognizer.mergeStops(emptyList()).isEmpty())
  }

  @Test fun gapOfTenMinutesMergesElevenDoesNot() {
    assertEquals(1, PlaceRecognizer.mergeStops(listOf(dwell(0, 20), dwell(30, 60))).size)
    assertEquals(2, PlaceRecognizer.mergeStops(listOf(dwell(0, 20), dwell(31, 60))).size)
  }

  @Test fun nearbyButDifferentPlaceDoesNotMergeAcrossTheRadius() {
    val far = SyntheticWeek.CAFE // ~1 km
    assertEquals(2, PlaceRecognizer.mergeStops(listOf(dwell(0, 20), dwell(25, 60, far))).size)
  }

  @Test fun unsortedInputIsHandled() {
    val merged = PlaceRecognizer.mergeStops(listOf(dwell(30, 60), dwell(0, 20)))
    assertEquals(listOf(0L, 60L), listOf(merged.single().startUtcMs / min, merged.single().endUtcMs / min))
  }

  // --- guessing needs enough evidence ---

  @Test fun twoNightsAreNotEnoughToGuessHomeThreeAre() {
    fun nights(n: Int) = (0 until n).map { d -> DwellRecord(SyntheticWeek.at(d, 22), SyntheticWeek.at(d + 1, 6), 1.0, 1.0) }
    val two = PlaceRecognizer.recognize(PlaceRecognitionInput(nights(2), tzOffsetMinutes = tz))
    val three = PlaceRecognizer.recognize(PlaceRecognitionInput(nights(3), tzOffsetMinutes = tz))
    assertNull(two.guesses.firstOrNull { it.category == PlaceCategory.HOME })
    assertEquals(3, three.guesses.single { it.category == PlaceCategory.HOME }.evidenceDays)
  }

  @Test fun weekendOnlyDaytimeIsNotWork() {
    val sat = SyntheticWeek.at(5, 9)
    val stays = (0..3).map { w ->
      DwellRecord(sat + w * 7 * 1440 * min, sat + w * 7 * 1440 * min + 8 * 60 * min, 1.0, 1.0)
    }
    val out = PlaceRecognizer.recognize(PlaceRecognitionInput(stays, tzOffsetMinutes = tz))
    assertNull(out.guesses.firstOrNull { it.category == PlaceCategory.WORK })
  }

  @Test fun timeZoneMovesTheWindow() {
    // 09:00-17:00 local on Mon-Wed at UTC+7 is a work pattern; read as UTC+0 it is 02:00-10:00 local, only 1 h inside 09-17.
    val stays = (0..2).map { d -> DwellRecord(SyntheticWeek.at(d, 9), SyntheticWeek.at(d, 17), 5.0, 5.0) }
    assertNotNull(PlaceRecognizer.recognize(PlaceRecognitionInput(stays, tzOffsetMinutes = tz)).guesses.firstOrNull())
    assertTrue(PlaceRecognizer.recognize(PlaceRecognitionInput(stays, tzOffsetMinutes = 0)).guesses.none { it.category == PlaceCategory.WORK })
  }

  @Test fun distanceIsAboutRightAndZeroForSamePoint() {
    assertEquals(0.0, PlaceRecognizer.distanceMeters(1.0, 1.0, 1.0, 1.0))
    val d = PlaceRecognizer.distanceMeters(13.7563, 100.5018, 13.8013, 100.5018)
    assertTrue(d in 4_900.0..5_100.0, "was $d")
  }

  @Test fun noDwellsGivesNothing() {
    val out = PlaceRecognizer.recognize(PlaceRecognitionInput(emptyList()))
    assertTrue(out.places.isEmpty() && out.visits.isEmpty() && out.guesses.isEmpty())
  }
}
