package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

internal class FakeEvents(events: List<CaptureEvent>) : CaptureEventRepository {
  private val all = events.toMutableList()
  override fun append(event: CaptureEvent) = all.add(event)
  override fun appendAll(events: List<CaptureEvent>) = events.count(::append)
  override fun query(input: CaptureQueryInput) =
    all.filter { it.timeUtcMs >= input.fromUtcMs && it.timeUtcMs < input.toUtcMs }.sortedWith(compareBy({ it.timeUtcMs }, { it.id }))
  override fun count() = all.size
  override fun delete(id: String) = all.removeAll { it.id == id }
  override fun purgeBefore(cutoffUtcMs: Long) = 0
}

internal class FakePlaces(private val places: List<PlaceRecord>) : PlaceRepository {
  override fun list() = places
  override fun addNew(places: List<PlaceRecord>) = 0
  override fun confirm(id: String, label: String, category: PlaceCategory) = false
  override fun delete(id: String) = false
}

internal class FakeLedger : DiaryLedgerRepository {
  val rows = mutableMapOf<String, DiaryLedgerEntry>()
  override fun find(lineId: String) = rows[lineId]
  override fun findByRecord(memoryDay: String, recordId: String) =
    rows.values.firstOrNull { it.memoryDay == memoryDay && it.memoryRecordId == recordId }
  override fun recordWritten(lineId: String, memoryDay: String, memoryRecordId: String) {
    rows[lineId] = DiaryLedgerEntry(lineId, memoryDay, memoryRecordId, false)
  }
  override fun markDeleted(lineId: String) {
    rows[lineId] = (rows[lineId] ?: DiaryLedgerEntry(lineId, "", "", false)).copy(deleted = true)
  }
}

class DiaryComposerTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val fs = FakeFileSystem()
  private var n = 0
  private val memory = MemoryStore(fs, "/mem".toPath()) { "r${++n}" }
  private val ledger = FakeLedger()
  private val weekPlaces = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), emptyList(), tz),
  ).places

  private fun near(p: PlaceRecord, at: Pair<Double, Double>) =
    PlaceRecognizer.distanceMeters(p.lat, p.lon, at.first, at.second) < 60

  /** Home and office confirmed by the user; cafe and gym left unnamed. */
  private val places = weekPlaces.map {
    when {
      near(it, SyntheticWeek.HOME) -> it.copy(confirmed = true, category = PlaceCategory.HOME)
      near(it, SyntheticWeek.OFFICE) -> it.copy(confirmed = true, category = PlaceCategory.WORK)
      else -> it
    }
  }

  private fun composer(extra: List<CaptureEvent> = emptyList(), known: List<PlaceRecord> = places) =
    DiaryComposer(FakeEvents(SyntheticWeek.events() + extra), FakePlaces(known), memory, ledger)

  private fun input(day: String, style: DiaryStyle = DiaryStyle()) = DiaryComposeInput(day, tz, style)

  private val tuesday = "2026-09-08"
  private val tuesdayLines = listOf(
    "08:15 ออกจากบ้าน",
    "09:00 ถึงที่ทำงาน ใช้เวลาเดินทางราว 45 นาที อยู่ราว 3 ชั่วโมงครึ่ง",
    "12:30 ออกจากที่ทำงาน",
    "12:40 ถึงสถานที่หนึ่ง ใช้เวลาเดินทางราว 10 นาที อยู่ราว 40 นาที",
    "13:20 ออกจากสถานที่หนึ่ง",
    "13:30 ถึงที่ทำงาน ใช้เวลาเดินทางราว 10 นาที อยู่ราว 4 ชั่วโมงครึ่ง",
    "18:00 ออกจากที่ทำงาน",
    "20:00 ถึงบ้าน",
  )

  private fun day(day: String, c: DiaryComposer = composer()) = c.compose(input(day)).written.map { it.text }

  @Test fun tuesdayRendersHumanReadableThai() {
    // the 7 minute store stop (18:10-18:17) is not in there; the 40 min lunch is
    assertEquals(tuesdayLines, day(tuesday))
  }

  @Test fun twoHourStopIsAnEventSevenMinuteStopIsNot() {
    assertTrue(day("2026-09-12").any { "อยู่ราว 2 ชั่วโมง" in it }, "Saturday 2h cafe stay")
    assertTrue(day(tuesday).none { "18:1" in it }, "store stop")
  }

  @Test fun threePlacesAndOneAppointmentReadWithoutAModel() {
    val appt = CaptureEvent("cal-1", SyntheticWeek.at(0, 15), CaptureSource.CalendarEntry("หมอฟัน"), endUtcMs = SyntheticWeek.at(0, 16))
    val out = composer(listOf(appt)).compose(input("2026-09-07"))
    val texts = out.written.map { it.text }
    assertTrue("15:00 มีนัด “หมอฟัน” ถึง 16:00" in texts, texts.toString())
    assertTrue(texts.any { it.contains("ถึงที่ทำงาน") })
    assertTrue(texts.any { it.contains("ถึงสถานที่หนึ่ง") && it.contains("40 นาที") }, "cafe")
    assertTrue(texts.any { it.contains("ถึงสถานที่หนึ่ง") && it.contains("1 ชั่วโมง") }, "gym")
    assertEquals(texts.sorted(), texts, "chronological: every line starts with HH:MM")
    assertTrue(texts.all { Regex("""^\d\d:\d\d """).containsMatchIn(it) })
    assertEquals(DiaryLineKind.CALENDAR, out.written.first { "หมอฟัน" in it.text }.kind)
  }

  @Test fun headerIsBuddhistEraButStorageIsCeUtc() {
    val out = composer().compose(input(tuesday))
    assertEquals("วันอังคารที่ 8 กันยายน พ.ศ. 2569", out.header)
    val first = out.written.first()
    assertEquals(SyntheticWeek.at(1, 8, 15), first.timeUtcMs)
    val rec = memory.readDay("2026-09-08").first { it.text == first.text }
    assertEquals(SyntheticWeek.at(1, 8, 15), rec.atEpochMs)
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, rec.provenance)
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, first.provenance)
    assertTrue(out.written.none { "2569" in it.text || "2026" in it.text })
  }

  @Test fun endingParticleIsAppliedToEveryLine() {
    val krap = composer().compose(input(tuesday, DiaryStyle(EndingParticle.KRAP))).written.map { it.text }
    assertEquals(tuesdayLines.map { it + "ครับ" }, krap)
  }

  @Test fun kaParticleAndNone() {
    val ka = composer().compose(input(tuesday, DiaryStyle(EndingParticle.KA))).written.map { it.text }
    assertEquals(tuesdayLines.map { it + "ค่ะ" }, ka)
    assertTrue(tuesdayLines.none { it.endsWith("ครับ") || it.endsWith("ค่ะ") })
  }

  @Test fun unknownPlacesNeverGetInventedNames() {
    // a guessed but unconfirmed place carrying a label must not be used, nor an unconfirmed home category
    val guessed = weekPlaces.map { it.copy(label = "Starbucks", category = PlaceCategory.HOME, confirmed = false) }
    val texts = day(tuesday, composer(known = guessed))
    assertTrue(texts.none { "Starbucks" in it || "บ้าน" in it || "ที่ทำงาน" in it }, texts.toString())
    assertTrue(texts.any { "สถานที่หนึ่ง" in it })
  }

  @Test fun userLabelBeatsCategoryPhrase() {
    val named = places.map { if (it.category == PlaceCategory.WORK) it.copy(label = "บริษัทสยาม") else it }
    assertTrue(day(tuesday, composer(known = named)).any { it.startsWith("09:00 ถึงบริษัทสยาม") })
  }

  @Test fun rerunningTheSameDayAddsNothing() {
    val c = composer()
    assertEquals(8, c.compose(input(tuesday)).written.size)
    val before = c.readDay(tuesday, tz)
    val again = c.compose(input(tuesday))
    assertTrue(again.written.isEmpty())
    assertEquals(8, again.existing)
    assertEquals(before, c.readDay(tuesday, tz))
  }

  @Test fun changingParticleDoesNotDuplicateLines() {
    val c = composer()
    c.compose(input(tuesday))
    assertTrue(c.compose(input(tuesday, DiaryStyle(EndingParticle.KA))).written.isEmpty())
    assertEquals(8, c.readDay(tuesday, tz).size)
  }

  @Test fun lineIdsAreStableAndIndependentOfStyle() {
    val a = composer().compose(input(tuesday)).written.map { it.id }
    val other = DiaryComposer(FakeEvents(SyntheticWeek.events()), FakePlaces(places), MemoryStore(FakeFileSystem(), "/m2".toPath()), FakeLedger())
    val b = other.compose(input(tuesday, DiaryStyle(EndingParticle.KRAP))).written.map { it.id }
    assertEquals(a, b)
    assertEquals(a.size, a.toSet().size)
    assertNotEquals(a[0], a[2])
  }

  @Test fun sourceEventIdsPointAtRealCaptureEvents() {
    val ids = SyntheticWeek.events().map { it.id }.toSet()
    val lines = composer().compose(input(tuesday)).written
    lines.forEach { assertTrue(it.sourceEventIds.isNotEmpty() && ids.containsAll(it.sourceEventIds), it.text) }
  }

  @Test fun deletedLineNeverComesBack() {
    val c = composer()
    val victim = c.compose(input(tuesday)).written[1]
    assertTrue(c.deleteLine(victim.id))
    assertFalse(c.readDay(tuesday, tz).any { it.text == victim.text })
    val again = c.compose(input(tuesday))
    assertTrue(again.written.isEmpty())
    assertEquals(1, again.suppressed)
    // even with another style and after the day files are wiped
    memory.deleteDay("2026-09-08")
    memory.deleteDay("2026-09-07")
    val fresh = c.compose(input(tuesday, DiaryStyle(EndingParticle.KA)))
    assertTrue(fresh.written.none { it.id == victim.id })
    assertFalse(c.readDay(tuesday, tz).any { it.text == victim.text })
  }

  @Test fun deletingByMemoryRecordIsHonoredToo() {
    val c = composer()
    c.compose(input(tuesday))
    val rec = c.readDay(tuesday, tz).first()
    assertTrue(c.deleteRecord(MemoryFormat.dayOf(rec.atEpochMs), rec.id))
    assertTrue(c.compose(input(tuesday)).written.isEmpty())
    assertEquals(7, c.readDay(tuesday, tz).size)
  }

  @Test fun localMidnightLineIsFoundByLocalDayEvenThoughFileIsUtcDay() {
    val late = CaptureEvent("cal-late", SyntheticWeek.at(1, 0, 30), CaptureSource.CalendarEntry("เที่ยวบินดึก"))
    val c = composer(listOf(late))
    c.compose(input(tuesday))
    assertTrue(c.readDay(tuesday, tz).any { "เที่ยวบินดึก" in it.text })
    assertFalse(c.readDay("2026-09-07", tz).any { "เที่ยวบินดึก" in it.text })
  }

  @Test fun emptyDayWritesNothing() {
    val out = DiaryComposer(FakeEvents(emptyList()), FakePlaces(emptyList()), memory, ledger).compose(input(tuesday))
    assertTrue(out.written.isEmpty())
    assertEquals("วันอังคารที่ 8 กันยายน พ.ศ. 2569", out.header)
  }

  @Test fun lostLedgerWriteDoesNotDuplicateLines() {
    val c = composer()
    c.compose(input(tuesday))
    ledger.rows.clear()
    assertTrue(c.compose(input(tuesday)).written.isEmpty())
    assertEquals(8, c.readDay(tuesday, tz).size)
  }
}
