package com.bbpillow.haku.surface.tasks

import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.appointmentTitle
import com.bbpillow.haku.capture.appointmentsOfDay
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.task.TaskQuadrant
import com.bbpillow.haku.task.TaskRecord
import com.bbpillow.haku.task.TaskRepository
import com.bbpillow.haku.task.TaskRules

/**
 * One row in a box. [dueText] is Thai: "วันนี้", "พรุ่งนี้", "6 พ.ย."; null = no due day.
 * [fromCalendar] = today's appointment: read-only here, it is changed in the calendar.
 */
data class TaskRowUi(
  val id: String,
  val title: String,
  val dueText: String?,
  val isOverdue: Boolean,
  val isDone: Boolean,
  val fromCalendar: Boolean = false,
)

data class TaskQuadrantUi(val quadrant: TaskQuadrant, val label: String, val rows: List<TaskRowUi>)

/** Always the four boxes, in [TaskQuadrant] order, even when empty. */
data class TasksUiState(val quadrants: List<TaskQuadrantUi>)

/** What the add/edit form holds. [dueDay] is local `YYYY-MM-DD`; null = no due day. */
data class TaskDraft(val title: String, val dueDay: String?, val important: Boolean)

/**
 * The Tasks screen. Stateless; call on the DB thread like every store user.
 * A box lists open tasks soonest due first (no due day last, then oldest first), then tasks finished today, struck through.
 * Tasks finished before today are not listed. Today's appointments still ahead lead the urgent box.
 */
class TasksPresenter(
  private val tasks: TaskRepository,
  private val tzOffsetMinutes: Int,
  private val clock: () -> Long,
  private val newId: () -> String,
  /** Null only in tests that predate it. */
  private val events: CaptureEventRepository? = null,
) {
  fun load(): TasksUiState {
    val now = clock()
    val todayDay = TaskRules.dayOf(now, tzOffsetMinutes)
    val tomorrowDay = TaskRules.dayOf(TaskRules.dueOfDay(todayDay, tzOffsetMinutes) + 1L, tzOffsetMinutes)

    val allTasks = tasks.all()
    val byQuadrant = allTasks.groupBy { TaskRules.quadrantOf(it, now) }

    val quadrantsUi = TaskQuadrant.entries.map { quadrant ->
      val quadrantTasks = byQuadrant[quadrant] ?: emptyList()

      val openRows = quadrantTasks
        .filter { it.doneUtcMs == null }
        .sortedWith { a, b ->
          when {
            a.dueUtcMs == null && b.dueUtcMs == null -> a.createdUtcMs.compareTo(b.createdUtcMs)
            a.dueUtcMs == null -> 1
            b.dueUtcMs == null -> -1
            a.dueUtcMs != b.dueUtcMs -> a.dueUtcMs.compareTo(b.dueUtcMs)
            else -> a.createdUtcMs.compareTo(b.createdUtcMs)
          }
        }
        .map { toRowUi(it, now, todayDay, tomorrowDay) }

      val doneRows = quadrantTasks
        .filter { it.doneUtcMs != null && TaskRules.dayOf(it.doneUtcMs, tzOffsetMinutes) == todayDay }
        .sortedBy { it.doneUtcMs }
        .map { toRowUi(it, now, todayDay, tomorrowDay) }

      TaskQuadrantUi(
        quadrant = quadrant,
        label = TaskRules.label(quadrant),
        rows = (if (quadrant == TaskQuadrant.URGENT) appointmentRows(todayDay, now) else emptyList()) + openRows + doneRows,
      )
    }

    return TasksUiState(quadrantsUi)
  }

  private fun appointmentRows(todayDay: String, now: Long): List<TaskRowUi> {
    val dayStart = TaskRules.dueOfDay(todayDay, tzOffsetMinutes) + 1L - DAY_MS
    return events?.appointmentsOfDay(dayStart).orEmpty()
      .filter { (it.endUtcMs ?: it.timeUtcMs) > now }
      .map {
        val time = MemoryFormat.formatUtc(it.timeUtcMs + tzOffsetMinutes * MIN_MS).substring(11, 16)
        TaskRowUi(it.id, it.appointmentTitle(), "วันนี้ $time", isOverdue = false, isDone = false, fromCalendar = true)
      }
  }

  /** The form's values for an existing task, or null. */
  fun draftOf(id: String): TaskDraft? {
    val record = tasks.find(id) ?: return null
    return TaskDraft(
      title = record.title,
      dueDay = record.dueUtcMs?.let { TaskRules.dayOf(it, tzOffsetMinutes) },
      important = record.important,
    )
  }

  /**
   * [id] null = new task; otherwise keeps that task's created and done times. Returns false, storing nothing, when the
   * title is blank or [id] no longer exists.
   */
  fun save(id: String?, draft: TaskDraft): Boolean {
    val cleanTitle = TaskRules.cleanTitle(draft.title) ?: return false
    val dueUtcMs = draft.dueDay?.let { TaskRules.dueOfDay(it, tzOffsetMinutes) }

    if (id == null) {
      tasks.put(
        TaskRecord(
          id = newId(),
          title = cleanTitle,
          dueUtcMs = dueUtcMs,
          important = draft.important,
          createdUtcMs = clock(),
          doneUtcMs = null,
        ),
      )
      return true
    }

    val existing = tasks.find(id) ?: return false
    tasks.put(
      existing.copy(
        title = cleanTitle,
        dueUtcMs = dueUtcMs,
        important = draft.important,
      ),
    )
    return true
  }

  /** Open becomes done now; done becomes open. Unknown id does nothing. */
  fun toggleDone(id: String) {
    val existing = tasks.find(id) ?: return
    tasks.put(
      existing.copy(
        doneUtcMs = if (existing.doneUtcMs == null) clock() else null,
      ),
    )
  }

  fun delete(id: String) {
    tasks.delete(id)
  }

  private fun toRowUi(
    task: TaskRecord,
    now: Long,
    todayDay: String,
    tomorrowDay: String,
  ): TaskRowUi {
    val isDone = task.doneUtcMs != null
    val dueText = task.dueUtcMs?.let { dueMs ->
      val day = TaskRules.dayOf(dueMs, tzOffsetMinutes)
      when (day) {
        todayDay -> "วันนี้"
        tomorrowDay -> "พรุ่งนี้"
        else -> ThaiDateFormat.shortDate(day)
      }
    }
    val isOverdue = !isDone && task.dueUtcMs != null && task.dueUtcMs < now
    return TaskRowUi(
      id = task.id,
      title = task.title,
      dueText = dueText,
      isOverdue = isOverdue,
      isDone = isDone,
    )
  }

  private companion object {
    const val MIN_MS = 60_000L
    const val DAY_MS = 86_400_000L
  }
}
