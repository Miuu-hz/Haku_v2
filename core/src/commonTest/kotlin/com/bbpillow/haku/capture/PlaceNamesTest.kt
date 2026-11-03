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
}
