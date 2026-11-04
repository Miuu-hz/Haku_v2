package com.bbpillow.haku.task

import com.bbpillow.haku.memory.MemoryFormat

/*
 * P3.7 shared contract: the four-box task list behind the Home card and the Tasks screen.
 * Owned by Claude: agents implement against it but do not change it.
 */

/** Urgency is a rule on the due time, importance is the user's own switch. */
enum class TaskQuadrant { IMPORTANT_URGENT, IMPORTANT, URGENT, SOMEDAY }

/**
 * One task. Stays on the device, and goes when deleted or with forget-everything.
 *
 * @property dueUtcMs null = no due time, so never urgent.
 * @property doneUtcMs null = still open.
 */
data class TaskRecord(
  val id: String,
  val title: String,
  val dueUtcMs: Long?,
  val important: Boolean,
  val createdUtcMs: Long,
  val doneUtcMs: Long? = null,
)

/** How many open tasks sit in each box. */
data class TaskCounts(val importantUrgent: Int = 0, val important: Int = 0, val urgent: Int = 0, val someday: Int = 0) {
  val total: Int get() = importantUrgent + important + urgent + someday
}

/** SQL lives in `TaskStore` (same DB as capture; not thread-safe, callers serialize). [clear] is called by ForgetEverything. */
interface TaskRepository {
  /** Every task, open and done, oldest first. */
  fun all(): List<TaskRecord>
  fun find(id: String): TaskRecord?

  /** Insert, or replace the row with the same id. */
  fun put(record: TaskRecord)
  fun delete(id: String)

  /** Removes done tasks finished before [cutoffUtcMs]; returns how many went. Open tasks are never touched. */
  fun deleteDoneBefore(cutoffUtcMs: Long): Int
  fun clear()
}

object TaskRules {
  const val URGENT_WINDOW_MS = 48L * 3_600_000L
  const val KEEP_DONE_MS = 30L * 86_400_000L
  const val MAX_TITLE_CHARS = 200
  private const val MIN_MS = 60_000L
  private const val DAY_MS = 86_400_000L

  /** Due within 48 hours, or already past. */
  fun isUrgent(task: TaskRecord, nowUtcMs: Long): Boolean =
    task.dueUtcMs != null && task.dueUtcMs - nowUtcMs <= URGENT_WINDOW_MS

  fun quadrantOf(task: TaskRecord, nowUtcMs: Long): TaskQuadrant = when {
    task.important && isUrgent(task, nowUtcMs) -> TaskQuadrant.IMPORTANT_URGENT
    task.important -> TaskQuadrant.IMPORTANT
    isUrgent(task, nowUtcMs) -> TaskQuadrant.URGENT
    else -> TaskQuadrant.SOMEDAY
  }

  fun label(quadrant: TaskQuadrant): String = when (quadrant) {
    TaskQuadrant.IMPORTANT_URGENT -> "สำคัญและเร่งด่วน"
    TaskQuadrant.IMPORTANT -> "สำคัญ"
    TaskQuadrant.URGENT -> "เร่งด่วน"
    TaskQuadrant.SOMEDAY -> "สักวันหนึ่ง"
  }

  /** Counts open tasks only. */
  fun counts(tasks: List<TaskRecord>, nowUtcMs: Long): TaskCounts {
    val byBox = tasks.filter { it.doneUtcMs == null }.groupingBy { quadrantOf(it, nowUtcMs) }.eachCount()
    return TaskCounts(
      importantUrgent = byBox[TaskQuadrant.IMPORTANT_URGENT] ?: 0,
      important = byBox[TaskQuadrant.IMPORTANT] ?: 0,
      urgent = byBox[TaskQuadrant.URGENT] ?: 0,
      someday = byBox[TaskQuadrant.SOMEDAY] ?: 0,
    )
  }

  /** Trims, joins lines and caps; blank gives null. */
  fun cleanTitle(raw: String): String? =
    raw.replace('\r', ' ').replace('\n', ' ').trim().take(MAX_TITLE_CHARS).trim().takeIf { it.isNotEmpty() }

  /**
   * The last millisecond of local [day] (`YYYY-MM-DD`).
   * ponytail: due is a whole day, no time of day; add a time field when a task needs "ก่อน 18:00".
   */
  fun dueOfDay(day: String, tzOffsetMinutes: Int): Long =
    requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } - tzOffsetMinutes * MIN_MS + DAY_MS - 1L

  /** The local day a due time falls on. */
  fun dayOf(utcMs: Long, tzOffsetMinutes: Int): String = MemoryFormat.dayOf(utcMs + tzOffsetMinutes * MIN_MS)
}
