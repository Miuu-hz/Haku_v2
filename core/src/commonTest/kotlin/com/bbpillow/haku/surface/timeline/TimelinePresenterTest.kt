package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class TimelinePresenterTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val tuesday = "2026-09-08"
  private val today = "2026-09-10"
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }
  private val ledger = FakeLedger()
  private val places = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), emptyList(), tz),
  ).places
  private val composer = DiaryComposer(FakeEvents(SyntheticWeek.events()), FakePlaces(places), memory, ledger)
  private val presenter = TimelinePresenter(composer, memory, tz)

  private fun composeTuesday() = composer.compose(DiaryComposeInput(tuesday, tz))

  @Test fun linesShowLocalTimeAndTextWithoutTheTimePrefix() {
    composeTuesday()
    val state = presenter.load(tuesday, today)
    assertEquals(8, state.lines.size)
    assertEquals("08:15", state.lines.first().time)
    assertEquals("ออกจากสถานที่หนึ่ง", state.lines.first().text) // unconfirmed places stay neutral
    assertTrue(state.lines.all { it.provenance == Provenance.INFERRED_FROM_USER_DATA })
  }

  @Test fun deletedLineNeverReappearsAfterRecomposeAndReload() {
    composeTuesday()
    val before = presenter.load(tuesday, today)
    val victim = before.lines[3]
    val after = presenter.delete(before, victim)
    assertEquals(7, after.lines.size)
    assertFalse(after.lines.any { it.text == victim.text })

    val again = composeTuesday()
    assertTrue(again.written.isEmpty())
    assertEquals(1, again.suppressed)
    val reloaded = TimelinePresenter(composer, memory, tz).load(tuesday, today)
    assertEquals(after.lines, reloaded.lines)
  }

  @Test fun editedLineIsUserTypedAndSurvivesRecompose() {
    composeTuesday()
    val before = presenter.load(tuesday, today)
    val line = before.lines[1]
    val after = presenter.edit(before, line, "  ถึงออฟฟิศ ประชุมเช้า  ")
    val edited = after.lines.single { it.text == "ถึงออฟฟิศ ประชุมเช้า" }
    assertEquals(Provenance.USER_TYPED, edited.provenance)
    assertEquals(line.time, edited.time)
    assertEquals(8, after.lines.size)

    composeTuesday() // the generated original must not come back next to the edit
    val reloaded = presenter.load(tuesday, today)
    assertEquals(after.lines, reloaded.lines)
    assertFalse(reloaded.lines.any { it.text == line.text })
  }

  @Test fun blankOrUnchangedEditIsIgnored() {
    composeTuesday()
    val state = presenter.load(tuesday, today)
    val line = state.lines[0]
    assertEquals(state, presenter.edit(state, line, "   "))
    assertEquals(state, presenter.edit(state, line, line.text))
  }

  @Test fun userTypedLineCanBeDeletedToo() {
    val at = 1_788_837_180_000L // 2026-09-08 10:13 Bangkok
    memory.appendDaily(DailyEventInput("10:13 ซื้อกาแฟ", Provenance.USER_TYPED, at))
    val state = presenter.load(tuesday, today)
    assertEquals(listOf("ซื้อกาแฟ"), state.lines.map { it.text })
    assertTrue(presenter.delete(state, state.lines[0]).lines.isEmpty())
  }

  @Test fun headerIsBuddhistEraAndStripEndsToday() {
    val state = presenter.load(tuesday, today)
    assertEquals("วันอังคารที่ 8 กันยายน พ.ศ. 2569", state.header)
    assertEquals(TimelineDate(today, "พฤ", 10), state.dates.last())
    assertEquals(30, state.dates.size)
    assertEquals(TimelineDate("2026-08-12", "พ", 12), state.dates.first())
  }
}
