package com.bbpillow.haku.surface.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapAreaTest {
  @Test
  fun noStaysGivesNullAndZeroDurationStaysAloneGiveNull() {
    assertNull(MapArea.usualLocation(emptyList()))
    assertNull(MapArea.usualLocation(listOf(MapArea.Stay(13.75, 100.5, 0L))))
    assertNull(MapArea.usualLocation(listOf(MapArea.Stay(13.75, 100.5, -60_000L))))
    assertNull(
      MapArea.usualLocation(
        listOf(
          MapArea.Stay(13.75, 100.5, 0L),
          MapArea.Stay(18.79, 98.98, -100L),
        )
      )
    )

    // A valid stay among zero/negative duration stays is found
    val valid = MapArea.Stay(13.7563, 100.5018, 3_600_000L)
    val result = assertNotNull(
      MapArea.usualLocation(
        listOf(
          MapArea.Stay(18.79, 98.98, 0L),
          valid,
          MapArea.Stay(18.79, 98.98, -1000L),
        )
      )
    )
    assertEquals(valid.lat, result.first, EPS)
    assertEquals(valid.lon, result.second, EPS)
  }

  @Test
  fun oneLongStayInBangkokWithSeveralShortStaysInChiangMaiStaysNearBangkok() {
    val bkkLat = 13.7563
    val bkkLon = 100.5018
    val bkkStay = MapArea.Stay(bkkLat, bkkLon, 7 * 86_400_000L) // 7 days in Bangkok
    val cmStays = listOf(
      MapArea.Stay(18.7883, 98.9853, 2 * 3_600_000L), // 2 hours in Chiang Mai
      MapArea.Stay(18.7900, 98.9900, 3 * 3_600_000L), // 3 hours in Chiang Mai
      MapArea.Stay(18.8000, 98.9800, 1 * 3_600_000L), // 1 hour in Chiang Mai
    )
    val stays = cmStays + bkkStay
    val location = assertNotNull(MapArea.usualLocation(stays))
    // The weighted median must be near Bangkok
    assertEquals(bkkLat, location.first, 0.01)
    assertEquals(bkkLon, location.second, 0.01)
  }

  @Test
  fun anyPointLiesInsideItsOwnCellWithAtLeastHalfDegreeToEveryEdgeOverThailandGrid() {
    var lat = 5.5
    while (lat <= 20.6) {
      var lon = 97.2
      while (lon <= 105.8) {
        val cell = MapArea.cellFor(lat, lon)
        assertTrue(cell.contains(lat, lon), "Cell ${cell.id} must contain point ($lat, $lon)")
        assertTrue(lat - cell.south >= 0.5 - EPS, "Distance from lat $lat to south ${cell.south} must be >= 0.5")
        assertTrue(cell.north - lat >= 0.5 - EPS, "Distance from north ${cell.north} to lat $lat must be >= 0.5")
        assertTrue(lon - cell.west >= 0.5 - EPS, "Distance from lon $lon to west ${cell.west} must be >= 0.5")
        assertTrue(cell.east - lon >= 0.5 - EPS, "Distance from east ${cell.east} to lon $lon must be >= 0.5")
        lon += 0.25
      }
      lat += 0.25
    }
  }

  @Test
  fun cellForOnTwoPointsVeryCloseTogetherOnEitherSideOfBoundaryGivesNeighbouringCellsWithDifferentIds() {
    // Boundary between step 17 (center 13.6) and step 18 (center 14.4) is at lat 14.0
    val lon = 100.8
    val delta = 1e-5
    val southOfBoundary = MapArea.cellFor(14.0 - delta, lon)
    val northOfBoundary = MapArea.cellFor(14.0 + delta, lon)

    assertTrue(southOfBoundary.id != northOfBoundary.id, "Cells across boundary must have different ids")
    assertEquals("n13_60_e100_80", southOfBoundary.id)
    assertEquals("n14_40_e100_80", northOfBoundary.id)
    assertEquals(MapArea.STEP_DEG, northOfBoundary.south - southOfBoundary.south, EPS)
    assertEquals(MapArea.STEP_DEG, northOfBoundary.north - southOfBoundary.north, EPS)
    assertEquals(southOfBoundary.west, northOfBoundary.west, EPS)
    assertEquals(southOfBoundary.east, northOfBoundary.east, EPS)
  }

  @Test
  fun theSamePointAlwaysGivesTheSameIdAndIdsAreUniqueAcrossCellsCovering() {
    val lat = 13.7563
    val lon = 100.5018
    val cell1 = MapArea.cellFor(lat, lon)
    val cell2 = MapArea.cellFor(lat, lon)
    assertEquals(cell1.id, cell2.id)
    assertEquals(cell1, cell2)

    val covering = MapArea.cellsCovering(5.5, 97.2, 20.6, 105.8)
    val ids = covering.map { it.id }
    assertEquals(ids.size, ids.toSet().size, "All cell ids in cellsCovering must be unique")
  }

  @Test
  fun cellsCoveringForThailandHasEveryCellOverlappingBoxAndNoCellOutsideIt() {
    val south = 5.5
    val west = 97.2
    val north = 20.6
    val east = 105.8

    val cells = MapArea.cellsCovering(south, west, north, east)
    assertTrue(cells.isNotEmpty(), "Thailand cellsCovering must not be empty")

    // Every cell must overlap the box
    cells.forEach { cell ->
      val overlaps = cell.south <= north && cell.north >= south && cell.west <= east && cell.east >= west
      assertTrue(overlaps, "Cell ${cell.id} (${cell.south}, ${cell.west}, ${cell.north}, ${cell.east}) must overlap box")
    }

    // No cell outside the box
    assertTrue(cells.none { it.north < south || it.south > north || it.east < west || it.west > east })

    // The covering must cover all of Thailand: every cell's bounding boundaries envelop the box
    val minLat = cells.minOf { it.south }
    val maxLat = cells.maxOf { it.north }
    val minLon = cells.minOf { it.west }
    val maxLon = cells.maxOf { it.east }
    assertTrue(minLat <= south && maxLat >= north)
    assertTrue(minLon <= west && maxLon >= east)
  }

  @Test
  fun negativeCoordinatesFormatWithSouthAndWest() {
    val cell = MapArea.cellFor(-13.6, -100.8)
    assertEquals("s13_60_w100_80", cell.id)
  }

  private companion object {
    const val EPS = 1e-9
  }
}
