package com.bbpillow.haku.surface.tasks

import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.task.TaskQuadrant
import com.bbpillow.haku.task.TaskRecord
import com.bbpillow.haku.task.TaskRepository
import com.bbpillow.haku.task.TaskRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksPresenterTest {
  private val tzOffsetMinutes = 420 // UTC+7
  // 2026-11-05 10:00:00 in UTC+7 (03:00:00 UTC)
  private val fixedNow = requireNotNull(MemoryFormat.parseUtc("2026-11-05T03:00:00.000Z"))
  private var idCounter = 0
  private fun nextId() = "id-${++idCounter}"

  private class InMemoryTaskRepository : TaskRepository {
    private val items = linkedMapOf<String, TaskRecord>()

    override fun all(): List<TaskRecord> = items.values.sortedWith(compareBy({ it.createdUtcMs }, { it.id }))
    override fun find(id: String): TaskRecord? = items[id]
    override fun put(record: TaskRecord) { items[record.id] = record }
    override fun delete(id: String) { items.remove(id) }
    override fun deleteDoneBefore(cutoffUtcMs: Long): Int {
      val toRemove = items.values.filter { it.doneUtcMs != null && it.doneUtcMs < cutoffUtcMs }
      toRemove.forEach { items.remove(it.id) }
      return toRemove.size
    }
    override fun clear() { items.clear() }
  }

  private fun createPresenter(repo: TaskRepository, clock: () -> Long = { fixedNow }) =
    TasksPresenter(repo, tzOffsetMinutes, clock, ::nextId)

  @Test fun fourBoxesAlwaysPresentInOrderEvenWhenEmpty() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)
    val state = presenter.load()

    assertEquals(4, state.quadrants.size)
    assertEquals(
      listOf(TaskQuadrant.IMPORTANT_URGENT, TaskQuadrant.IMPORTANT, TaskQuadrant.URGENT, TaskQuadrant.SOMEDAY),
      state.quadrants.map { it.quadrant },
    )
    assertEquals(
      listOf("สำคัญและเร่งด่วน", "สำคัญ", "เร่งด่วน", "สักวันหนึ่ง"),
      state.quadrants.map { it.label },
    )
    assertTrue(state.quadrants.all { it.rows.isEmpty() })
  }

  @Test fun orderingInsideBoxOpenByDueThenCreatedThenDoneTodayByDone() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val dueToday = TaskRules.dueOfDay("2026-11-05", tzOffsetMinutes)
    val dueTomorrow = TaskRules.dueOfDay("2026-11-06", tzOffsetMinutes)
    val doneEarlierToday = requireNotNull(MemoryFormat.parseUtc("2026-11-05T01:00:00.000Z"))
    val doneLaterToday = requireNotNull(MemoryFormat.parseUtc("2026-11-05T02:00:00.000Z"))

    // Quadrant: IMPORTANT_URGENT (important = true, urgent = true)
    val taskDueTomorrow = TaskRecord("t1", "Due tomorrow", dueTomorrow, true, createdUtcMs = 100L)
    val taskDueToday1 = TaskRecord("t2", "Due today first created", dueToday, true, createdUtcMs = 200L)
    val taskDueToday2 = TaskRecord("t3", "Due today second created", dueToday, true, createdUtcMs = 300L)
    val taskDoneEarlier = TaskRecord("t4", "Done earlier today", dueToday, true, createdUtcMs = 50L, doneUtcMs = doneEarlierToday)
    val taskDoneLater = TaskRecord("t5", "Done later today", dueToday, true, createdUtcMs = 60L, doneUtcMs = doneLaterToday)

    listOf(taskDueTomorrow, taskDueToday1, taskDueToday2, taskDoneEarlier, taskDoneLater).forEach(repo::put)

    val state = presenter.load()
    val iuQuadrant = state.quadrants.first { it.quadrant == TaskQuadrant.IMPORTANT_URGENT }
    val rowIds = iuQuadrant.rows.map { it.id }

    assertEquals(listOf("t2", "t3", "t1", "t4", "t5"), rowIds)
  }

  @Test fun nullDueTasksAreOrderedLastAmongOpenTasks() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val dueFuture = TaskRules.dueOfDay("2026-11-10", tzOffsetMinutes)
    val withDue = TaskRecord("t1", "With due", dueFuture, false, createdUtcMs = 300L)
    val nullDueFirst = TaskRecord("t2", "No due created first", null, false, createdUtcMs = 100L)
    val nullDueSecond = TaskRecord("t3", "No due created second", null, false, createdUtcMs = 200L)

    listOf(withDue, nullDueFirst, nullDueSecond).forEach(repo::put)

    val state = presenter.load()
    val someday = state.quadrants.first { it.quadrant == TaskQuadrant.SOMEDAY }
    assertEquals(listOf("t1", "t2", "t3"), someday.rows.map { it.id })
  }

  @Test fun taskDoneYesterdayNotListedAndDoneTodayListedAfterOpen() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val dueToday = TaskRules.dueOfDay("2026-11-05", tzOffsetMinutes)
    val doneYesterday = requireNotNull(MemoryFormat.parseUtc("2026-11-04T08:00:00.000Z"))
    val doneToday = requireNotNull(MemoryFormat.parseUtc("2026-11-05T01:00:00.000Z"))

    val openTask = TaskRecord("open", "Open task", dueToday, true, createdUtcMs = 100L)
    val doneTodayTask = TaskRecord("done_today", "Finished today", dueToday, true, createdUtcMs = 200L, doneUtcMs = doneToday)
    val doneYesterdayTask = TaskRecord("done_yesterday", "Finished yesterday", dueToday, true, createdUtcMs = 300L, doneUtcMs = doneYesterday)

    listOf(openTask, doneTodayTask, doneYesterdayTask).forEach(repo::put)

    val state = presenter.load()
    val allLoadedIds = state.quadrants.flatMap { it.rows }.map { it.id }

    assertEquals(listOf("open", "done_today"), allLoadedIds)
    assertFalse(allLoadedIds.contains("done_yesterday"))
  }

  @Test fun dueTextTodayTomorrowDateAndNull() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val dueToday = TaskRules.dueOfDay("2026-11-05", tzOffsetMinutes)
    val dueTomorrow = TaskRules.dueOfDay("2026-11-06", tzOffsetMinutes)
    val dueNov8 = TaskRules.dueOfDay("2026-11-08", tzOffsetMinutes)

    val tToday = TaskRecord("t_today", "Task today", dueToday, false, 100L)
    val tTomorrow = TaskRecord("t_tomorrow", "Task tomorrow", dueTomorrow, false, 200L)
    val tNov8 = TaskRecord("t_nov8", "Task 8 Nov", dueNov8, false, 300L)
    val tNoDue = TaskRecord("t_nodue", "Task no due", null, false, 400L)

    listOf(tToday, tTomorrow, tNov8, tNoDue).forEach(repo::put)

    val rowsById = presenter.load().quadrants.flatMap { it.rows }.associateBy { it.id }

    assertEquals("วันนี้", rowsById["t_today"]?.dueText)
    assertEquals("พรุ่งนี้", rowsById["t_tomorrow"]?.dueText)
    assertEquals("8 พ.ย.", rowsById["t_nov8"]?.dueText)
    assertNull(rowsById["t_nodue"]?.dueText)
  }

  @Test fun overdueOnlyWhenOpen() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val pastDue = fixedNow - 3600_000L
    val futureDue = fixedNow + 3600_000L
    val doneToday = requireNotNull(MemoryFormat.parseUtc("2026-11-05T01:00:00.000Z"))

    val openOverdue = TaskRecord("t1", "Open overdue", pastDue, false, 100L)
    val openNotOverdue = TaskRecord("t2", "Open not overdue", futureDue, false, 200L)
    val openNoDue = TaskRecord("t3", "Open no due", null, false, 300L)
    val doneOverdueTime = TaskRecord("t4", "Done overdue time", pastDue, false, 400L, doneUtcMs = doneToday)

    listOf(openOverdue, openNotOverdue, openNoDue, doneOverdueTime).forEach(repo::put)

    val rowsById = presenter.load().quadrants.flatMap { it.rows }.associateBy { it.id }

    assertTrue(rowsById["t1"]?.isOverdue == true)
    assertFalse(rowsById["t2"]?.isOverdue == true)
    assertFalse(rowsById["t3"]?.isOverdue == true)
    assertFalse(rowsById["t4"]?.isOverdue == true)
  }

  @Test fun saveRejectsBlankTitleAndUnknownIdWithoutStoring() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val blankSaved = presenter.save(null, TaskDraft("   ", "2026-11-05", false))
    assertFalse(blankSaved)

    val unknownSaved = presenter.save("non-existent-id", TaskDraft("Valid title", "2026-11-05", false))
    assertFalse(unknownSaved)

    assertTrue(repo.all().isEmpty())
  }

  @Test fun saveOnExistingTaskKeepsCreatedAndDoneTimes() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val initial = TaskRecord(
      id = "task-keep",
      title = "Initial Title",
      dueUtcMs = null,
      important = false,
      createdUtcMs = 123456L,
      doneUtcMs = 789012L,
    )
    repo.put(initial)

    val saved = presenter.save("task-keep", TaskDraft("New Clean Title", "2026-11-06", true))
    assertTrue(saved)

    val updated = repo.find("task-keep")
    assertNotNull(updated)
    assertEquals("New Clean Title", updated.title)
    assertEquals(TaskRules.dueOfDay("2026-11-06", tzOffsetMinutes), updated.dueUtcMs)
    assertTrue(updated.important)
    assertEquals(123456L, updated.createdUtcMs)
    assertEquals(789012L, updated.doneUtcMs)
  }

  @Test fun toggleDoneBothWays() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val task = TaskRecord("task-toggle", "Item to toggle", null, false, 100L, doneUtcMs = null)
    repo.put(task)

    presenter.toggleDone("task-toggle")
    assertEquals(fixedNow, repo.find("task-toggle")?.doneUtcMs)

    presenter.toggleDone("task-toggle")
    assertNull(repo.find("task-toggle")?.doneUtcMs)

    presenter.toggleDone("unknown-task-id")
    assertEquals(1, repo.all().size)
  }

  @Test fun delete() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    repo.put(TaskRecord("task-del", "Delete me", null, false, 100L))
    assertNotNull(repo.find("task-del"))

    presenter.delete("task-del")
    assertNull(repo.find("task-del"))
  }

  @Test fun draftOfRoundTrip() {
    val repo = InMemoryTaskRepository()
    val presenter = createPresenter(repo)

    val dueMs = TaskRules.dueOfDay("2026-11-05", tzOffsetMinutes)
    repo.put(TaskRecord("t1", "Buy groceries", dueMs, true, 100L))
    repo.put(TaskRecord("t2", "Call friend", null, false, 200L))

    val draft1 = presenter.draftOf("t1")
    assertNotNull(draft1)
    assertEquals("Buy groceries", draft1.title)
    assertEquals("2026-11-05", draft1.dueDay)
    assertTrue(draft1.important)

    val draft2 = presenter.draftOf("t2")
    assertNotNull(draft2)
    assertEquals("Call friend", draft2.title)
    assertNull(draft2.dueDay)
    assertFalse(draft2.important)

    assertNull(presenter.draftOf("unknown-id"))
  }
}
