package com.bbpillow.haku.capture

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun store() = PlaceStore(BundledSQLiteDriver().open(":memory:").also(opened::add))

  @Test fun confirmedLabelIsStoredAndNotOverwrittenByRerun() {
    val store = store()
    val guess = PlaceRecord("p1", 13.75, 100.5)
    assertEquals(1, store.addNew(listOf(guess)))
    assertNull(store.list().single().label)
    assertTrue(store.confirm("p1", "บ้าน", PlaceCategory.HOME))
    assertEquals(0, store.addNew(listOf(guess)), "recognition re-run must not clobber the confirmation")
    val saved = store.list().single()
    assertEquals(PlaceRecord("p1", 13.75, 100.5, "บ้าน", PlaceCategory.HOME, true), saved)
  }

  @Test fun confirmUnknownAndDelete() {
    val store = store()
    assertFalse(store.confirm("nope", "x", PlaceCategory.OTHER))
    store.addNew(listOf(PlaceRecord("p1", 1.0, 2.0)))
    assertTrue(store.delete("p1"))
    assertFalse(store.delete("p1"))
    assertTrue(store.list().isEmpty())
  }

  @Test fun recognitionOutputRoundTripsThroughTheStoreAndIsStable() {
    val store = store()
    val input = PlaceRecognitionInput(PlaceRecognizer.pairDwells(SyntheticWeek.events()), tzOffsetMinutes = SyntheticWeek.TZ_OFFSET_MIN)
    val first = PlaceRecognizer.recognize(input)
    store.addNew(first.places)
    first.guesses.forEach { g -> store.confirm(g.placeId, g.category.name, g.category) }
    val second = PlaceRecognizer.recognize(input.copy(knownPlaces = store.list()))
    assertEquals(4, second.places.size)
    assertTrue(second.guesses.isEmpty())
  }
}
