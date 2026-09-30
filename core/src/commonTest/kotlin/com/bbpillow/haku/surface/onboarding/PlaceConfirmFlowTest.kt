package com.bbpillow.haku.surface.onboarding

import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognitionInput
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.SyntheticWeek.at
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.surface.home.HomeBriefPresenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private class MemPlaces : PlaceRepository {
  val rows = linkedMapOf<String, PlaceRecord>()
  override fun list() = rows.values.toList()
  override fun addNew(places: List<PlaceRecord>) = places.count { rows.putIfAbsent(it.id, it) == null }
  override fun confirm(id: String, label: String, category: PlaceCategory): Boolean {
    val p = rows[id] ?: return false
    rows[id] = p.copy(label = label, category = category, confirmed = true)
    return true
  }
  override fun delete(id: String) = rows.remove(id) != null
}

/** The whole B7 path on real presenters: guess -> confirm -> stored -> diary names it -> home brief measures it. */
class PlaceConfirmFlowTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private var n = 0
  private val events = FakeEvents(SyntheticWeek.events())
  private val places = MemPlaces()
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }
  private val composer = DiaryComposer(events, places, memory, FakeLedger())
  private val onboarding = OnboardingPresenter(ProfileStore(FakeFileSystem(), "/p/profile.txt".toPath()), places)

  private fun guesses() = PlaceRecognizer.recognize(
    PlaceRecognitionInput(PlaceRecognizer.pairDwells(events.query(CaptureQueryInput(0, Long.MAX_VALUE))), places.list(), tz),
  ).guesses

  private fun confirm(category: PlaceCategory, label: String) {
    val guess = guesses().first { it.category == category }
    onboarding.confirmPlace(onboarding.start(8, guess), label)
  }

  @Test fun confirmingGuessesStoresThePlacesAndTheDiaryAndHomeBriefUseThem() {
    confirm(PlaceCategory.HOME, "บ้าน")
    confirm(PlaceCategory.WORK, "ที่ทำงาน")

    val stored = places.list().filter { it.confirmed }
    assertEquals(setOf(PlaceCategory.HOME, PlaceCategory.WORK), stored.map { it.category }.toSet())

    val lines = composer.compose(DiaryComposeInput("2026-09-08", tz)).written.map { it.text }
    assertTrue(lines.any { it.startsWith("20:00 ถึงบ้าน") }, lines.toString())
    assertTrue(lines.any { it.contains("ถึงที่ทำงาน") }, lines.toString())

    val stats = HomeBriefPresenter(composer, events, places, tz).load(at(1, 20)).stats
    assertNotNull(assertNotNull(stats).outOfHomeFraction)
  }

  @Test fun placeIdsAreStableAcrossRuns() {
    composer.compose(DiaryComposeInput("2026-09-08", tz))
    val first = places.list().map { it.id }.toSet()
    composer.compose(DiaryComposeInput("2026-09-09", tz))
    assertTrue(first.isNotEmpty() && places.list().map { it.id }.containsAll(first))
    val again = PlaceRecognizer.recognize(
      PlaceRecognitionInput(PlaceRecognizer.pairDwells(events.query(CaptureQueryInput(0, Long.MAX_VALUE))), places.list(), tz),
    )
    assertTrue(again.places.map { it.id }.toSet().containsAll(first))
  }
}
