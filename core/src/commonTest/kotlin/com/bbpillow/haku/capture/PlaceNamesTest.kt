package com.bbpillow.haku.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceNamesTest {
  @Test fun labelSkipsHouseNumbersAndPlusCodes() {
    assertEquals("ถนนพระราม 9", PlaceNames.label(GeocodedAddressInput("123/4", "123/4", "ถนนพระราม 9", "แขวงห้วยขวาง")))
    assertEquals("เซ็นทรัล พระราม 9", PlaceNames.label(GeocodedAddressInput("เซ็นทรัล พระราม 9", thoroughfare = "ถนนพระราม 9")))
    assertEquals("แขวงห้วยขวาง", PlaceNames.label(GeocodedAddressInput("8Q9R+2X", thoroughfare = "Unnamed Road", subLocality = "แขวงห้วยขวาง")))
    assertNull(PlaceNames.label(GeocodedAddressInput("10310", postalCode = "10310")))
  }

  @Test fun everyPlaceGetsItsOwnNameLettersByFirstVisit() {
    val places = listOf(
      PlaceRecord("place-1", 0.0, 0.0, autoName = "สถานที่ A"),
      PlaceRecord("place-2", 0.0, 0.0, autoName = "ถนนสีลม"),
      PlaceRecord("place-3", 0.0, 0.0),
      PlaceRecord("place-4", 0.0, 0.0),
      PlaceRecord("place-5", 0.0, 0.0),
      PlaceRecord("place-6", 0.0, 0.0), // lookup failed: not in found, left for the next run
    )
    val out = PlaceNames.assign(places, mapOf("place-1" to "x", "place-3" to null, "place-4" to "ถนนสีลม", "place-5" to null))
    assertEquals(mapOf("place-3" to "สถานที่ B", "place-4" to "ถนนสีลม แห่งที่ 2", "place-5" to "สถานที่ C"), out)
    assertTrue(PlaceNames.isFallback("สถานที่ AA") && !PlaceNames.isFallback("ถนนสีลม"))
  }

  @Test fun locationFieldWinsOverTitleNoun() {
    val event = CalendarRowInput(
      eventId = 1L,
      beginUtcMs = 1_000L,
      endUtcMs = 5_000L,
      title = "ประชุม รพ.ศรีนครินทร์",
      location = "ศาลากลาง",
    )
    assertEquals("ศาลากลาง", PlaceNames.candidateFromEvent(event))
  }

  @Test fun titleNounExtractedWithProperNameUntilNextSpace() {
    val e1 = CalendarRowInput(1L, 1_000L, 5_000L, title = "พบผู้ว่า ศาลากลาง")
    assertEquals("ศาลากลาง", PlaceNames.candidateFromEvent(e1))

    val e2 = CalendarRowInput(2L, 1_000L, 5_000L, title = "ตรวจตา รพ.ศรีนครินทร์ 9 โมง")
    assertEquals("รพ.ศรีนครินทร์", PlaceNames.candidateFromEvent(e2))
  }

  @Test fun longLocationGivesItsBuildingElseItsRoom() {
    fun loc(l: String) = PlaceNames.candidateFromEvent(CalendarRowInput(1L, 0L, 1L, title = "ประชุม", location = l))
    assertEquals("ศาลากลางจังหวัดขอนแก่น", loc("ห้องประชุมพระยามหาอำมาตย์ ชั้น 3 ศาลากลางจังหวัดขอนแก่น"))
    assertEquals("คณะวิทยาศาสตร์", loc("ห้องประชุมสิริคุณากร ชั้น 2 คณะวิทยาศาสตร์ มข."))
    assertEquals("ห้องประชุมใหญ่ชั้นห้า", loc("ห้องประชุมใหญ่ชั้นห้า อาคารที่มีชื่อยาวมากจนเกินสามสิบสองตัวอักษรแน่นอน"))
    assertEquals(null, loc("ห้องประชุมที่มีชื่อยาวมากจนเกินสามสิบสองตัวอักษรแน่นอนเลย"))
  }

  @Test fun thaiDigitTypedWithShiftBetweenLettersIsTheLetter() {
    assertEquals("คณะเทคโนโลยีการเกษตร", PlaceNames.unshiftTypos("คณะเทคโนโลยีการเกษ๖ร"))
    assertEquals("อาคาร ๒ ชั้น ๓", PlaceNames.unshiftTypos("อาคาร ๒ ชั้น ๓"))
  }

  @Test fun noOverlapYieldsNoName() {
    val stays = listOf(10_000L to 20_000L)
    val events = listOf(
      CalendarRowInput(1L, 30_000L, 40_000L, title = "พบผู้ว่า ศาลากลาง"),
    )
    assertNull(PlaceNames.calendarCandidate(stays, events))
  }

  @Test fun allDayEventIgnored() {
    val stays = listOf(10_000L to 20_000L)
    val allDayEvent = CalendarRowInput(1L, 0L, 86_400_000L, title = "พบผู้ว่า ศาลากลาง", allDay = true)
    assertNull(PlaceNames.candidateFromEvent(allDayEvent))
    assertNull(PlaceNames.calendarCandidate(stays, listOf(allDayEvent)))
  }

  @Test fun conflictingCandidatesPicksMajorityTieKeepsNull() {
    val stay = listOf(0L to 10_000_000L)
    val e1 = CalendarRowInput(1L, 1_000_000L, 2_000_000L, title = "พบผู้ว่า ศาลากลาง")
    val e2 = CalendarRowInput(2L, 3_000_000L, 4_000_000L, title = "ติดต่องาน ศาลากลาง")
    val e3 = CalendarRowInput(3L, 5_000_000L, 6_000_000L, title = "ตรวจตา รพ.ศรีนครินทร์")
    assertEquals("ศาลากลาง", PlaceNames.calendarCandidate(stay, listOf(e1, e2, e3)))

    // Tie: 1 vs 1 -> returns null so geocoder name is kept
    assertNull(PlaceNames.calendarCandidate(stay, listOf(e1, e3)))
  }

  @Test fun calendarUpgradesGeocoderOrFallbackName() {
    val places = listOf(
      PlaceRecord("p1", 0.0, 0.0, autoName = "ถนนสุขุมวิท"),
      PlaceRecord("p2", 0.0, 0.0, autoName = "สถานที่ A"),
      PlaceRecord("p3", 0.0, 0.0, label = "ที่ทำงาน", confirmed = true),
    )
    val assigned = PlaceNames.assignCalendar(places, mapOf("p1" to "ศาลากลาง", "p2" to "รพ.ศรีนครินทร์", "p3" to "ศาลากลาง"))
    assertEquals(mapOf("p1" to "ศาลากลาง", "p2" to "รพ.ศรีนครินทร์"), assigned)
  }

  @Test fun overlapThresholdFiftyPercentOrThirtyMinutes() {
    val e20m = CalendarRowInput(1L, 0L, 20 * 60_000L, title = "ศาลากลาง")
    assertTrue(PlaceNames.overlaps(0L, 10 * 60_000L, e20m))
    assertTrue(!PlaceNames.overlaps(0L, 9 * 60_000L, e20m))

    val e2h = CalendarRowInput(2L, 0L, 120 * 60_000L, title = "ศาลากลาง")
    assertTrue(PlaceNames.overlaps(0L, 30 * 60_000L, e2h))
    assertTrue(!PlaceNames.overlaps(0L, 29 * 60_000L, e2h))
  }
}
