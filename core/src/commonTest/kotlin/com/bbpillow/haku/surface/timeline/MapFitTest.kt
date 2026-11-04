package com.bbpillow.haku.surface.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapFitTest {
  @Test
  fun noPointsGivesNull() {
    assertNull(MapFit.of(emptyList(), widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))
  }

  @Test
  fun onePointGivesMaxZoomCentredOnPoint() {
    val fit = assertNotNull(MapFit.of(listOf(13.75 to 100.5), widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))
    assertEquals(13.75, fit.lat, 1e-6)
    assertEquals(100.5, fit.lon, 1e-6)
    assertEquals(MapFit.MAX_ZOOM, fit.zoom)
  }

  @Test
  fun multiplePointsOnOneSpotGivesMaxZoom() {
    val points = listOf(13.75 to 100.5, 13.75 to 100.5, 13.75 to 100.5)
    val fit = assertNotNull(MapFit.of(points, widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))
    assertEquals(13.75, fit.lat, 1e-6)
    assertEquals(100.5, fit.lon, 1e-6)
    assertEquals(MapFit.MAX_ZOOM, fit.zoom)
  }

  @Test
  fun twoPointsFarApartGiveSmallerZoomThanTwoPointsCloseTogether() {
    val close = listOf(13.7500 to 100.5000, 13.7505 to 100.5005)
    val closeFit = assertNotNull(MapFit.of(close, widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))

    val far = listOf(13.75 to 100.50, 14.10 to 100.80)
    val farFit = assertNotNull(MapFit.of(far, widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))

    assertTrue(farFit.zoom < closeFit.zoom, "Expected far zoom (${farFit.zoom}) < close zoom (${closeFit.zoom})")
  }

  @Test
  fun veryFarPairIsClampedAtMinZoom() {
    val veryFar = listOf(13.75 to 100.50, 51.50 to -0.12)
    val fit = assertNotNull(MapFit.of(veryFar, widthPx = 800, heightPx = 400, tileSizePx = 256, paddingPx = 32))
    assertEquals(MapFit.MIN_ZOOM, fit.zoom)
  }

  @Test
  fun widerCardAllowsZoomAtLeastAsLarge() {
    val points = listOf(13.70 to 100.40, 13.70 to 100.60)
    val narrowFit = assertNotNull(MapFit.of(points, widthPx = 300, heightPx = 400, tileSizePx = 256, paddingPx = 32))
    val wideFit = assertNotNull(MapFit.of(points, widthPx = 1200, heightPx = 400, tileSizePx = 256, paddingPx = 32))

    assertTrue(wideFit.zoom >= narrowFit.zoom, "Expected wide zoom (${wideFit.zoom}) >= narrow zoom (${narrowFit.zoom})")
  }

  @Test
  fun centreIsMiddleOfBoundingBox() {
    val points = listOf(10.0 to 100.0, 20.0 to 110.0)
    val fit = assertNotNull(MapFit.of(points, widthPx = 800, heightPx = 800, tileSizePx = 256, paddingPx = 32))
    assertEquals(15.0, fit.lat, 1e-6)
    assertEquals(105.0, fit.lon, 1e-6)
  }
}
