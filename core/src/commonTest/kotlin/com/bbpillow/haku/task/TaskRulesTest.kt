package com.bbpillow.haku.task

import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRulesTest {
  @Test fun urgencyExactAt48HoursJustOverPastAndNoDue() {
    val now = 1700000000000L
    val base = TaskRecord(id = "1", title = "Task", dueUtcMs = null, important = false, createdUtcMs = now)

    // Urgency exactly at 48 hours is urgent
    val exact48h = base.copy(dueUtcMs = now + TaskRules.URGENT_WINDOW_MS)
    assertTrue(TaskRules.isUrgent(exact48h, now))

    // Just over is not urgent
    val justOver48h = base.copy(dueUtcMs = now + TaskRules.URGENT_WINDOW_MS + 1L)
    assertFalse(TaskRules.isUrgent(justOver48h, now))

    // Past is urgent
    val past = base.copy(dueUtcMs = now - 1L)
    assertTrue(TaskRules.isUrgent(past, now))

    // No due is not urgent
    val noDue = base.copy(dueUtcMs = null)
    assertFalse(TaskRules.isUrgent(noDue, now))
  }

  @Test fun allFourQuadrants() {
    val now = 1700000000000L
    val urgentDue = now + 3600_000L
    val notUrgentDue = now + TaskRules.URGENT_WINDOW_MS + 3600_000L

    val importantUrgent = TaskRecord("1", "A", urgentDue, important = true, createdUtcMs = now)
    val important = TaskRecord("2", "B", notUrgentDue, important = true, createdUtcMs = now)
    val urgent = TaskRecord("3", "C", urgentDue, important = false, createdUtcMs = now)
    val someday = TaskRecord("4", "D", notUrgentDue, important = false, createdUtcMs = now)
    val somedayNoDue = TaskRecord("5", "E", null, important = false, createdUtcMs = now)

    assertEquals(TaskQuadrant.IMPORTANT_URGENT, TaskRules.quadrantOf(importantUrgent, now))
    assertEquals(TaskQuadrant.IMPORTANT, TaskRules.quadrantOf(important, now))
    assertEquals(TaskQuadrant.URGENT, TaskRules.quadrantOf(urgent, now))
    assertEquals(TaskQuadrant.SOMEDAY, TaskRules.quadrantOf(someday, now))
    assertEquals(TaskQuadrant.SOMEDAY, TaskRules.quadrantOf(somedayNoDue, now))
  }

  @Test fun countsIgnoreDoneTasks() {
    val now = 1700000000000L
    val urgentDue = now + 3600_000L
    val notUrgentDue = now + TaskRules.URGENT_WINDOW_MS + 3600_000L

    val tasks = listOf(
      TaskRecord("1", "Open IU", urgentDue, important = true, createdUtcMs = now, doneUtcMs = null),
      TaskRecord("2", "Done IU", urgentDue, important = true, createdUtcMs = now, doneUtcMs = now),
      TaskRecord("3", "Open Imp", notUrgentDue, important = true, createdUtcMs = now, doneUtcMs = null),
      TaskRecord("4", "Done Imp", notUrgentDue, important = true, createdUtcMs = now, doneUtcMs = now),
      TaskRecord("5", "Open Urg", urgentDue, important = false, createdUtcMs = now, doneUtcMs = null),
      TaskRecord("6", "Done Urg", urgentDue, important = false, createdUtcMs = now, doneUtcMs = now),
      TaskRecord("7", "Open Someday", notUrgentDue, important = false, createdUtcMs = now, doneUtcMs = null),
      TaskRecord("8", "Done Someday", notUrgentDue, important = false, createdUtcMs = now, doneUtcMs = now),
    )

    val counts = TaskRules.counts(tasks, now)
    assertEquals(1, counts.importantUrgent)
    assertEquals(1, counts.important)
    assertEquals(1, counts.urgent)
    assertEquals(1, counts.someday)
    assertEquals(4, counts.total)
  }

  @Test fun cleanTitleBlankNewlinesAndLengthCap() {
    assertNull(TaskRules.cleanTitle(""))
    assertNull(TaskRules.cleanTitle("   "))
    assertNull(TaskRules.cleanTitle(" \n \r\n \t "))

    assertEquals("Line one Line two Line three", TaskRules.cleanTitle("Line one\nLine two\r\nLine three"))

    val longTitle = "a".repeat(250)
    val cleaned = TaskRules.cleanTitle(longTitle)
    assertNotNull(cleaned)
    assertEquals(TaskRules.MAX_TITLE_CHARS, cleaned.length)
    assertEquals("a".repeat(TaskRules.MAX_TITLE_CHARS), cleaned)
  }

  @Test fun dueOfDayAtUtcPlus7For20261105EqualsLastMillisecondOfLocalDay() {
    val tz = 420
    val due = TaskRules.dueOfDay("2026-11-05", tz)
    val expectedUtcMs = requireNotNull(MemoryFormat.parseUtc("2026-11-05T16:59:59.999Z"))
    assertEquals(expectedUtcMs, due)
  }

  @Test fun dayOfRoundTripsWithDueOfDay() {
    val tz = 420
    val days = listOf("2026-01-01", "2026-11-05", "2026-12-31", "2028-02-29")
    for (day in days) {
      val dueMs = TaskRules.dueOfDay(day, tz)
      val roundTrip = TaskRules.dayOf(dueMs, tz)
      assertEquals(day, roundTrip)
    }
  }
}
