package com.bbpillow.haku.task

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun openDb(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also(opened::add)

  @Test fun roundTripWithNullAndNonNullDueAndDone() {
    val store = TaskStore(openDb())
    val task1 = TaskRecord(
      id = "t1",
      title = "Submit monthly report",
      dueUtcMs = null,
      important = false,
      createdUtcMs = 1700000000000L,
      doneUtcMs = null,
    )
    val task2 = TaskRecord(
      id = "t2",
      title = "Pay utility bill",
      dueUtcMs = 1700086400000L,
      important = true,
      createdUtcMs = 1700000001000L,
      doneUtcMs = 1700050000000L,
    )

    store.put(task1)
    store.put(task2)

    val loaded1 = store.find("t1")
    val loaded2 = store.find("t2")

    assertEquals(task1, loaded1)
    assertEquals(task2, loaded2)

    val all = store.all()
    assertEquals(listOf(task1, task2), all)
  }

  @Test fun putReplacesById() {
    val store = TaskStore(openDb())
    val original = TaskRecord(
      id = "t1",
      title = "Original title",
      dueUtcMs = 1000L,
      important = false,
      createdUtcMs = 500L,
    )
    store.put(original)
    assertEquals(original, store.find("t1"))

    val updated = TaskRecord(
      id = "t1",
      title = "Updated title",
      dueUtcMs = 2000L,
      important = true,
      createdUtcMs = 500L,
      doneUtcMs = 1500L,
    )
    store.put(updated)

    assertEquals(updated, store.find("t1"))
    assertEquals(listOf(updated), store.all())
  }

  @Test fun delete() {
    val store = TaskStore(openDb())
    val task1 = TaskRecord("t1", "Item 1", null, false, 100L)
    val task2 = TaskRecord("t2", "Item 2", null, false, 200L)
    store.put(task1)
    store.put(task2)

    store.delete("t1")
    assertNull(store.find("t1"))
    assertEquals(task2, store.find("t2"))
    assertEquals(listOf(task2), store.all())
  }

  @Test fun deleteDoneBeforeKeepsOpenTasksAndNewerDoneTasksAndReturnsCount() {
    val store = TaskStore(openDb())
    val cutoff = 2000L

    val openTask1 = TaskRecord("open1", "Open with due", dueUtcMs = 1000L, important = false, createdUtcMs = 100L, doneUtcMs = null)
    val openTask2 = TaskRecord("open2", "Open without due", dueUtcMs = null, important = true, createdUtcMs = 150L, doneUtcMs = null)
    val oldDone1 = TaskRecord("done1", "Done long ago", dueUtcMs = 500L, important = false, createdUtcMs = 200L, doneUtcMs = 1000L)
    val oldDone2 = TaskRecord("done2", "Done just before cutoff", dueUtcMs = 800L, important = false, createdUtcMs = 300L, doneUtcMs = 1999L)
    val boundaryDone = TaskRecord("done3", "Done exactly at cutoff", dueUtcMs = 1500L, important = false, createdUtcMs = 400L, doneUtcMs = 2000L)
    val newerDone = TaskRecord("done4", "Done after cutoff", dueUtcMs = 2500L, important = true, createdUtcMs = 500L, doneUtcMs = 2001L)

    listOf(openTask1, openTask2, oldDone1, oldDone2, boundaryDone, newerDone).forEach(store::put)

    val removedCount = store.deleteDoneBefore(cutoff)
    assertEquals(2, removedCount)

    assertNotNull(store.find("open1"))
    assertNotNull(store.find("open2"))
    assertNull(store.find("done1"))
    assertNull(store.find("done2"))
    assertNotNull(store.find("done3"))
    assertNotNull(store.find("done4"))

    val remaining = store.all()
    assertEquals(listOf(openTask1, openTask2, boundaryDone, newerDone), remaining)
  }

  @Test fun clear() {
    val store = TaskStore(openDb())
    store.put(TaskRecord("t1", "Item 1", null, false, 100L))
    store.put(TaskRecord("t2", "Item 2", null, true, 200L))

    store.clear()
    assertTrue(store.all().isEmpty())
    assertNull(store.find("t1"))
    assertNull(store.find("t2"))
  }
}
