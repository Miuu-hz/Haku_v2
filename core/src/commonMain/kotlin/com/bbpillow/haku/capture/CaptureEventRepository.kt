package com.bbpillow.haku.capture

interface CaptureEventRepository {
  /** False when the id is already stored or the user deleted it earlier (deleted events never come back). */
  fun append(event: CaptureEvent): Boolean

  /** Returns how many were actually written. */
  fun appendAll(events: List<CaptureEvent>): Int

  /** Ordered by time then id. */
  fun query(input: CaptureQueryInput): List<CaptureEvent>

  fun count(): Int

  /** User-initiated delete; remembers the id so it is never written back. */
  fun delete(id: String): Boolean

  /** Age-based retention. Not a user deletion, so nothing is remembered. Returns rows removed. */
  fun purgeBefore(cutoffUtcMs: Long): Int
}
