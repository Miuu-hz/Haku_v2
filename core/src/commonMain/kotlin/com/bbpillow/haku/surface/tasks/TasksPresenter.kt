package com.bbpillow.haku.surface.tasks

import com.bbpillow.haku.task.TaskQuadrant
import com.bbpillow.haku.task.TaskRepository

/** One row in a box. [dueText] is Thai: "วันนี้", "พรุ่งนี้", "6 พ.ย."; null = no due day. */
data class TaskRowUi(
  val id: String,
  val title: String,
  val dueText: String?,
  val isOverdue: Boolean,
  val isDone: Boolean,
)

data class TaskQuadrantUi(val quadrant: TaskQuadrant, val label: String, val rows: List<TaskRowUi>)

/** Always the four boxes, in [TaskQuadrant] order, even when empty. */
data class TasksUiState(val quadrants: List<TaskQuadrantUi>)

/** What the add/edit form holds. [dueDay] is local `YYYY-MM-DD`; null = no due day. */
data class TaskDraft(val title: String, val dueDay: String?, val important: Boolean)

/**
 * The Tasks screen. Stateless; call on the DB thread like every store user.
 * A box lists open tasks soonest due first (no due day last, then oldest first), then tasks finished today, struck through.
 * Tasks finished before today are not listed.
 */
class TasksPresenter(
  private val tasks: TaskRepository,
  private val tzOffsetMinutes: Int,
  private val clock: () -> Long,
  private val newId: () -> String,
) {
  fun load(): TasksUiState = TODO("agy: brief-home-tasks-store.md")

  /** The form's values for an existing task, or null. */
  fun draftOf(id: String): TaskDraft? = TODO("agy: brief-home-tasks-store.md")

  /**
   * [id] null = new task; otherwise keeps that task's created and done times. Returns false, storing nothing, when the
   * title is blank or [id] no longer exists.
   */
  fun save(id: String?, draft: TaskDraft): Boolean = TODO("agy: brief-home-tasks-store.md")

  /** Open becomes done now; done becomes open. Unknown id does nothing. */
  fun toggleDone(id: String): Unit = TODO("agy: brief-home-tasks-store.md")

  fun delete(id: String): Unit = TODO("agy: brief-home-tasks-store.md")
}
