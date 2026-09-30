package com.bbpillow.haku.surface.timeline

import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryLineKind
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.capture.diary.LineTypeKey
import com.bbpillow.haku.capture.diary.PlaceRef
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.thinking.summary.FakeSummaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** P3.2 pass criterion: delete the same type of line three times and the system stops creating it. */
class LineSuppressionTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val days = listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11")
  private val today = "2026-09-12"
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }
  private val ledger = FakeLedger()
  private val places = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), emptyList(), tz),
  ).places
  private val composer = DiaryComposer(FakeEvents(SyntheticWeek.events()), FakePlaces(places), memory, ledger)
  private val presenter = TimelinePresenter(composer, memory, FakeSummaries(), tz)

  private fun compose(day: String) = composer.compose(DiaryComposeInput(day, tz))
  private fun officeArrival(day: String) = presenter.load(day, today).lines.firstOrNull { it.time == "09:00" }

  private fun deleteOfficeArrival(day: String): TimelineUiState {
    compose(day)
    val state = presenter.load(day, today)
    return presenter.delete(state, state.lines.first { it.time == "09:00" })
  }

  @Test fun threeDeletesOfTheSameTypeStopThatTypeOnLaterDays() {
    days.take(3).forEach { deleteOfficeArrival(it) }
    assertEquals(1, composer.suppressedTypes().size)

    val thursday = compose(days[3])
    assertTrue(thursday.written.none { it.text.startsWith("09:00") })
    assertTrue(thursday.suppressed >= 1)
    assertEquals(null, officeArrival(days[3]))
    assertTrue(presenter.load(days[3], today).lines.any { it.time == "08:15" }) // other types untouched
  }

  @Test fun twoDeletesAreNotEnough() {
    days.take(2).forEach { deleteOfficeArrival(it) }
    compose(days[2])
    assertTrue(composer.suppressedTypes().isEmpty())
    assertEquals("09:00", officeArrival(days[2])?.time)
  }

  @Test fun kindAndPlaceBothDefineTheType() {
    val a = PlaceRef(placeId = "a")
    assertNotEquals(LineTypeKey.place(DiaryLineKind.ARRIVE, a), LineTypeKey.place(DiaryLineKind.ARRIVE, PlaceRef(placeId = "b")))
    assertNotEquals(LineTypeKey.place(DiaryLineKind.ARRIVE, a), LineTypeKey.place(DiaryLineKind.DEPART, a))
  }

  @Test fun calendarTitlesMatchIgnoringCaseAndSpacing() {
    assertEquals(LineTypeKey.calendar("Stand  Up "), LineTypeKey.calendar("stand up"))
    assertNotEquals(LineTypeKey.calendar("standup"), LineTypeKey.calendar("dentist"))
  }

  @Test fun undoRestoresTheLineAndGivesTheStrikeBack() {
    days.take(2).forEach { deleteOfficeArrival(it) }
    val third = deleteOfficeArrival(days[2])
    assertEquals(1, composer.suppressedTypes().size)

    val undone = presenter.undoDelete(third)
    assertEquals("09:00", undone.lines.firstOrNull { it.time == "09:00" }?.time)
    assertTrue(composer.suppressedTypes().isEmpty())
    compose(days[3])
    assertEquals("09:00", officeArrival(days[3])?.time) // not suppressed any more
  }

  @Test fun undoWithNothingDeletedIsANoOp() {
    compose(days[0])
    val state = presenter.load(days[0], today)
    assertEquals(state, presenter.undoDelete(state))
  }

  @Test fun undoneLineIsNotRewrittenAsDuplicate() {
    val state = deleteOfficeArrival(days[0])
    presenter.undoDelete(state)
    val again = compose(days[0])
    assertTrue(again.written.isEmpty())
    assertEquals(1, presenter.load(days[0], today).lines.count { it.time == "09:00" })
  }

  @Test fun unsuppressBringsTheTypeBackOnNextCompose() {
    days.take(3).forEach { deleteOfficeArrival(it) }
    val type = composer.suppressedTypes().single()
    assertEquals(3, type.deletes)
    composer.unsuppress(type.typeKey)
    assertTrue(composer.suppressedTypes().isEmpty())

    compose(days[3])
    assertEquals("09:00", officeArrival(days[3])?.time)
    deleteOfficeArrival(days[4]) // a fresh count: one delete is not enough again
    assertTrue(composer.suppressedTypes().isEmpty())
  }

  @Test fun editingALineIsNotAStrike() {
    days.take(3).forEach {
      compose(it)
      val s = presenter.load(it, today)
      presenter.edit(s, s.lines.first { l -> l.time == "09:00" }, "ถึงออฟฟิศ")
    }
    assertTrue(composer.suppressedTypes().isEmpty())
    assertFalse(compose(days[3]).written.isEmpty())
  }
}
