package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognitionOutput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.thinking.summary.FakeSummaries
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class TimelineMapTest {
  private val home = PlaceRecord("h", 13.7563, 100.5018, label = null, category = PlaceCategory.HOME, confirmed = true)
  private val office = PlaceRecord("o", 13.8013, 100.5018, label = "ออฟฟิศสีลม", category = PlaceCategory.WORK, confirmed = true)
  private val cafe = PlaceRecord("c", 13.7563, 100.5108, label = "ร้านลับ", category = PlaceCategory.WORK) // a guess, not confirmed

  private fun visit(p: PlaceRecord, start: Long, end: Long) = PlaceVisitRecord(p.id, start, end)
  private fun out(vararg v: PlaceVisitRecord) = PlaceRecognitionOutput(listOf(home, office, cafe), v.toList(), emptyList())

  @Test fun stopsAreInVisitOrderAndRowsGetTheSameNumbers() {
    val (map, numbers) = assertNotNull(
      TimelineMap.build(listOf(10L, 20L, 30L, 40L), out(visit(office, 20, 30), visit(home, 0, 10), visit(cafe, 40, 50))),
    )
    assertEquals(listOf(1, 2, 3), map.points.map { it.number })
    assertEquals(listOf("บ้าน", "ออฟฟิศสีลม", "สถานที่หนึ่ง"), map.points.map { it.label })
    assertEquals(mapOf(10L to 1, 20L to 2, 30L to 2, 40L to 3), numbers)
  }

  @Test fun onlyConfirmedPlacesAreNamedOrIconned() {
    val (map, _) = assertNotNull(TimelineMap.build(listOf(10L, 20L, 40L), out(visit(home, 0, 10), visit(office, 20, 30), visit(cafe, 40, 50))))
    assertEquals(listOf("บ้าน", "ออฟฟิศสีลม", "สถานที่หนึ่ง"), map.points.map { it.label })
    assertEquals(listOf(PlaceCategory.HOME, PlaceCategory.WORK, PlaceCategory.OTHER), map.points.map { it.category })
  }

  @Test fun aStayWithoutALiveRowIsNotOnTheMap() {
    // the cafe's rows were deleted: only its times are missing from the timeline
    assertNull(TimelineMap.build(listOf(10L), out(visit(home, 0, 10), visit(cafe, 40, 50))))
    val (map, _) = assertNotNull(TimelineMap.build(listOf(10L, 20L), out(visit(home, 0, 10), visit(office, 20, 30), visit(cafe, 40, 50))))
    assertEquals(2, map.points.size)
  }

  @Test fun hiddenUnderTwoDistinctPlaces() {
    assertNull(TimelineMap.build(emptyList(), out()))
    assertNull(TimelineMap.build(listOf(10L), out(visit(home, 0, 10))))
    assertNull(TimelineMap.build(listOf(10L, 20L), out(visit(home, 0, 10), visit(home, 20, 30)))) // home, out, home: one spot
  }

  @Test fun projectionIsNorthUpCentredAndInsideThePadding() {
    val xy = TimelineMap.project(listOf(home.lat to home.lon, office.lat to office.lon, cafe.lat to cafe.lon))
    xy.forEach { (x, y) ->
      assertTrue(x * TimelineMap.ASPECT >= TimelineMap.PADDING - EPS && x * TimelineMap.ASPECT <= TimelineMap.ASPECT - TimelineMap.PADDING + EPS)
      assertTrue(y >= TimelineMap.PADDING - EPS && y <= 1 - TimelineMap.PADDING + EPS)
    }
    assertTrue(xy[1].second < xy[0].second) // office is north of home: higher on the card
    assertTrue(xy[2].first > xy[0].first) // cafe is east of home: further right
    // the tall side fills the height exactly (5 km north beats 1 km east at this aspect)
    assertEquals(TimelineMap.PADDING, xy[1].second, EPS)
    assertEquals(1 - TimelineMap.PADDING, xy[0].second, EPS)
    // the box is centred horizontally
    val minX = xy.minOf { it.first }
    val maxX = xy.maxOf { it.first }
    assertEquals(0.5, (minX + maxX) / 2, EPS)
  }

  @Test fun oneScaleForBothAxes() {
    // a square of ground: equal degrees of lat and of lon / cos(lat) must come out as equal card distances
    val lat = 13.75
    val d = 0.01
    val xy = TimelineMap.project(listOf(lat to 100.5, lat + d to 100.5, lat to 100.5 + d / cos(lat * PI / 180)))
    val dy = abs(xy[1].second - xy[0].second)
    val dx = abs(xy[2].first - xy[0].first) * TimelineMap.ASPECT
    assertEquals(dy, dx, 0.02)
  }

  @Test fun presenterNumbersSyntheticTuesdayAndDropsDeletedEvents() {
    val tz = SyntheticWeek.TZ_OFFSET_MIN
    val day = "2026-09-08"
    val events = FakeEvents(SyntheticWeek.events())
    val places = PlaceRecognizer.recognize(PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), emptyList(), tz)).places
    val memory = MemoryStore(FakeFileSystem(), "/mem".toPath())
    val composer = DiaryComposer(events, FakePlaces(places), memory, FakeLedger())
    val presenter = TimelinePresenter(composer, memory, FakeSummaries(), tz)
    assertNull(presenter.load(day, "2026-09-10").map) // no rows yet, so no map
    composer.compose(DiaryComposeInput(day, tz))
    val state = presenter.load(day, "2026-09-10")
    val map = assertNotNull(state.map)
    assertTrue(map.points.size >= 2)
    assertEquals(map.points.map { it.number }.toSet(), state.lines.mapNotNull { it.mapNumber }.toSet())
    assertTrue(map.points.all { it.label == "สถานที่หนึ่ง" }) // nothing confirmed in the synthetic week

    // the user deletes the capture events behind stop 2: it never appears again
    val victimTimes = state.lines.filter { it.mapNumber == 2 }.map { it.atEpochMs }.toSet()
    SyntheticWeek.events().filter { it.timeUtcMs in victimTimes }.forEach { events.delete(it.id) }
    val after = presenter.load(day, "2026-09-10")
    assertEquals(map.points.size - 1, after.map?.points?.size ?: 1)
    assertTrue(after.lines.filter { it.atEpochMs in victimTimes }.all { it.mapNumber == null })
  }

  private companion object {
    const val EPS = 1e-9
  }
}
