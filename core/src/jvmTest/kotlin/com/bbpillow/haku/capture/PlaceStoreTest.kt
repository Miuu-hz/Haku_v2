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

  @Test fun oldTableGainsAutoNameSetOnce() {
    val db = BundledSQLiteDriver().open(":memory:").also(opened::add)
    db.prepare("CREATE TABLE places(id TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, label TEXT, category TEXT NOT NULL, confirmed INTEGER NOT NULL)").use { it.step() }
    db.prepare("INSERT INTO places VALUES('p1', 1.0, 2.0, NULL, 'OTHER', 0)").use { it.step() }
    val store = PlaceStore(db)
    assertNull(store.list().single().autoName)
    assertTrue(store.setAutoName("p1", "ถนนสีลม"))
    assertFalse(store.setAutoName("p1", "สถานที่ A"), "named once")
    assertFalse(store.setAutoName("nope", "x"))
    assertTrue(store.setAutoName("p1", "ศาลากลาง", replace = true), "calendar name replaces")
    assertEquals("ศาลากลาง", PlaceStore(db).list().single().autoName)
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
