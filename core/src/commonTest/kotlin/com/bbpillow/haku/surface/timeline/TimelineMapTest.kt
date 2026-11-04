package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.OpenStay
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognitionOutput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryTemplates
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.memory.MemoryFormat
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
    // the cafe's rows were deleted: only home's time remains, cafe must not appear on the map
    val (map1, _) = assertNotNull(TimelineMap.build(listOf(10L), out(visit(home, 0, 10), visit(cafe, 40, 50))))
    assertEquals(1, map1.points.size)
    assertEquals("บ้าน", map1.points.single().label)

    val (map2, _) = assertNotNull(TimelineMap.build(listOf(10L, 20L), out(visit(home, 0, 10), visit(office, 20, 30), visit(cafe, 40, 50))))
    assertEquals(2, map2.points.size)
    assertEquals(listOf("บ้าน", "ออฟฟิศสีลม"), map2.points.map { it.label })
  }

  @Test fun noStaysAndNoCurrentGivesNull() {
    assertNull(TimelineMap.build(emptyList(), out()))
  }

  @Test fun oneStopGivesOneCentredPoint() {
    val (map, numbers) = assertNotNull(TimelineMap.build(listOf(10L), out(visit(home, 0, 10))))
    assertEquals(1, map.points.size)
    val point = map.points.single()
    assertEquals(1, point.number)
    assertEquals(0.5, point.x, EPS)
    assertEquals(0.5, point.y, EPS)
    assertEquals("บ้าน", point.label)
    assertNull(point.since)
    assertEquals(mapOf(10L to 1), numbers)
  }

  @Test fun homeOutHomeGivesTwoPointsOnTheSameSpot() {
    val (map, numbers) = assertNotNull(TimelineMap.build(listOf(10L, 20L), out(visit(home, 0, 10), visit(home, 20, 30))))
    assertEquals(2, map.points.size)
    assertEquals(listOf(1, 2), map.points.map { it.number })
    assertEquals(0.5, map.points[0].x, EPS)
    assertEquals(0.5, map.points[0].y, EPS)
    assertEquals(0.5, map.points[1].x, EPS)
    assertEquals(0.5, map.points[1].y, EPS)
    assertEquals(mapOf(10L to 1, 20L to 2), numbers)
  }

  @Test fun onlyCurrentStayGivesOnePointWithSinceSetNoNumbersForRows() {
    val current = OpenStay(100L, home.lat, home.lon)
    val (map, numbers) = assertNotNull(TimelineMap.build(emptyList(), out(), current, "ตั้งแต่ 10:00"))
    assertEquals(1, map.points.size)
    val point = map.points.single()
    assertEquals(1, point.number)
    assertEquals("ตั้งแต่ 10:00", point.since)
    assertEquals(0.5, point.x, EPS)
    assertEquals(0.5, point.y, EPS)
    assertEquals("บ้าน", point.label)
    assertTrue(numbers.isEmpty())
  }

  @Test fun twoStopsPlusCurrentCurrentIsNumberThreeAndLast() {
    val current = OpenStay(50L, cafe.lat, cafe.lon)
    val (map, numbers) = assertNotNull(
      TimelineMap.build(listOf(10L, 30L), out(visit(home, 0, 10), visit(office, 20, 30)), current, "ตั้งแต่ 12:00"),
    )
    assertEquals(3, map.points.size)
    assertEquals(listOf(1, 2, 3), map.points.map { it.number })
    assertEquals(mapOf(10L to 1, 30L to 2), numbers)
    assertNull(map.points[0].since)
    assertNull(map.points[1].since)
    assertEquals("ตั้งแต่ 12:00", map.points[2].since)
  }

  @Test fun currentNearConfirmedPlaceUsesThatLabelFarUsesUnknownPlace() {
    val nearHome = OpenStay(100L, home.lat + 0.0001, home.lon)
    val (map1, _) = assertNotNull(TimelineMap.build(emptyList(), out(), nearHome, "ตั้งแต่ 10:00"))
    assertEquals("บ้าน", map1.points.single().label)
    assertEquals(PlaceCategory.HOME, map1.points.single().category)

    val farAway = OpenStay(100L, 14.0, 101.0)
    val (map2, _) = assertNotNull(TimelineMap.build(emptyList(), out(), farAway, "ตั้งแต่ 10:00"))
    assertEquals(DiaryTemplates.UNKNOWN_PLACE, map2.points.single().label)
    assertEquals(PlaceCategory.OTHER, map2.points.single().category)
  }

  @Test fun openDwellStartWithoutEndReturnedStartThenEndGivesNullStartWithNoPlaceGivesNull() {
    val place = EventPlace(13.75, 100.5)
    val startWithPlace = CaptureEvent("s1", 100L, CaptureSource.DwellStart, place)
    val end = CaptureEvent("e1", 200L, CaptureSource.DwellEnd, place)
    val startNoPlace = CaptureEvent("s2", 300L, CaptureSource.DwellStart, place = null)

    assertEquals(startWithPlace, PlaceRecognizer.openDwell(listOf(startWithPlace)))
    assertNull(PlaceRecognizer.openDwell(listOf(startWithPlace, end)))
    assertNull(PlaceRecognizer.openDwell(listOf(startNoPlace)))
  }

  @Test fun openStayFindsStayFromTwoDaysEarlierAndEndAfterDayEndDoesNotCloseIt() {
    val tz = 420
    val startMs = requireNotNull(MemoryFormat.parseUtc("2026-09-08T03:00:00.000Z"))
    val place = EventPlace(13.75, 100.5)
    val startEvent = CaptureEvent("s-early", startMs, CaptureSource.DwellStart, place)
    val afterEndMs = requireNotNull(MemoryFormat.parseUtc("2026-09-11T01:00:00.000Z"))
    val endEvent = CaptureEvent("e-late", afterEndMs, CaptureSource.DwellEnd, place)

    val events = FakeEvents(listOf(startEvent, endEvent))
    val composer = DiaryComposer(events, FakePlaces(emptyList()), MemoryStore(FakeFileSystem(), "/m".toPath()), FakeLedger())

    val stay = assertNotNull(composer.openStay("2026-09-10", tz))
    assertEquals(startMs, stay.startUtcMs)
    assertEquals(13.75, stay.lat)
    assertEquals(100.5, stay.lon)
  }

  @Test fun presenterCurrentStayAndMapEmptyStates() {
    val tz = 420
    val today = "2026-09-10"
    val pastDay = "2026-09-08"
    val place = EventPlace(home.lat, home.lon)
    val startMs = requireNotNull(MemoryFormat.parseUtc("2026-09-10T03:00:00.000Z"))
    val startEvent = CaptureEvent("s-today", startMs, CaptureSource.DwellStart, place)

    val events = FakeEvents(listOf(startEvent))
    val memory = MemoryStore(FakeFileSystem(), "/m".toPath())
    val composer = DiaryComposer(events, FakePlaces(listOf(home)), memory, FakeLedger())

    // 1. today with an open stay has a current point and mapEmpty == null
    val presenterOn = TimelinePresenter(composer, memory, FakeSummaries(), tz, captureOn = { true })
    val stateToday = presenterOn.load(today, today)
    assertNull(stateToday.mapEmpty)
    val currentPt = assertNotNull(stateToday.map).points.single()
    assertEquals(1, currentPt.number)
    assertEquals("ตั้งแต่ 10:00", currentPt.since)

    // 2. a past day has no current point
    val statePast = presenterOn.load(pastDay, today)
    assertNull(statePast.map)
    assertEquals(TimelineMapEmpty.NO_STAYS, statePast.mapEmpty)

    // 3. captureOn = false gives no current point and CAPTURE_OFF when nothing else draws
    val presenterOff = TimelinePresenter(composer, memory, FakeSummaries(), tz, captureOn = { false })
    val stateOff = presenterOff.load(today, today)
    assertNull(stateOff.map)
    assertEquals(TimelineMapEmpty.CAPTURE_OFF, stateOff.mapEmpty)

    // 4. empty day gives NO_STAYS
    val emptyComposer = DiaryComposer(FakeEvents(emptyList()), FakePlaces(listOf(home)), memory, FakeLedger())
    val presenterEmpty = TimelinePresenter(emptyComposer, memory, FakeSummaries(), tz, captureOn = { true })
    val stateEmpty = presenterEmpty.load(today, today)
    assertNull(stateEmpty.map)
    assertEquals(TimelineMapEmpty.NO_STAYS, stateEmpty.mapEmpty)
  }

  @Test fun presenterWordsSinceWithShortDateForEarlierDay() {
    val tz = 420
    val today = "2026-09-10"
    val startMs = requireNotNull(MemoryFormat.parseUtc("2026-09-08T13:00:00.000Z")) // 20:00 Bangkok on 2026-09-08
    val startEvent = CaptureEvent("s-earlier", startMs, CaptureSource.DwellStart, EventPlace(home.lat, home.lon))

    val events = FakeEvents(listOf(startEvent))
    val memory = MemoryStore(FakeFileSystem(), "/m".toPath())
    val composer = DiaryComposer(events, FakePlaces(listOf(home)), memory, FakeLedger())
    val presenter = TimelinePresenter(composer, memory, FakeSummaries(), tz, captureOn = { true })

    val state = presenter.load(today, today)
    val pt = assertNotNull(state.map).points.single()
    assertEquals("ตั้งแต่ 8 ก.ย. 20:00", pt.since)
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
