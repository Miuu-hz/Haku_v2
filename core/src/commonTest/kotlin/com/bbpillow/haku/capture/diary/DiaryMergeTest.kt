package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.memory.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** A stay composed early, then the user comes back within the merge gap: ids must not drift, stale lines must go. */
class DiaryMergeTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val day = "2026-09-07"
  private val loc = EventPlace(SyntheticWeek.OFFICE.first, SyntheticWeek.OFFICE.second)
  private val events = FakeEvents(emptyList())
  private val ledger = FakeLedger()
  private val composer = DiaryComposer(events, FakePlaces(emptyList()), MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }, ledger)
  private var n = 0

  private fun stay(startH: Int, startM: Int, endH: Int, endM: Int) {
    val s = SyntheticWeek.at(0, startH, startM)
    val e = SyntheticWeek.at(0, endH, endM)
    events.append(CaptureEvent("dwell-start-$s", s, CaptureSource.DwellStart, loc))
    events.append(CaptureEvent("dwell-end-$e", e, CaptureSource.DwellEnd, loc))
  }

  private fun lines() = composer.readDay(day, tz).map { it.text }
  private fun compose() = composer.compose(DiaryComposeInput(day, tz))

  @Test fun mergedStayKeepsOneArrivalAndDropsTheStaleDeparture() {
    stay(9, 0, 12, 0)
    compose()
    assertTrue(lines().any { it.startsWith("12:00 ออกจาก" ) })
    stay(12, 8, 18, 0)
    compose()
    val after = lines()
    assertEquals(1, after.count { it.startsWith("09:00 ถึง") }, after.toString())
    assertTrue(after.none { it.startsWith("12:00 ออกจาก") }, after.toString())
    assertEquals(1, after.count { it.startsWith("18:00 ออกจาก") }, after.toString())
    assertTrue(compose().written.isEmpty())
  }

  @Test fun deletedArrivalStaysDeletedAfterTheStayMerges() {
    stay(9, 0, 12, 0)
    val arrival = compose().written.first { it.kind == DiaryLineKind.ARRIVE }
    composer.deleteLine(arrival.id)
    stay(12, 8, 18, 0)
    compose()
    assertTrue(lines().none { it.startsWith("09:00 ถึง") }, lines().toString())
  }
}
