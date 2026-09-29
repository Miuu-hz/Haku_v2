package com.bbpillow.haku.capture.diary

/**
 * Remembers which diary lines were written and which the user deleted (mirrors CaptureEventStore's tombstones).
 * A deleted line stays here forever, so re-composing the day never brings it back. Also counts deletions per line
 * type ([LineTypeKey]); at [DiaryThresholds.SUPPRESS_AFTER_DELETES] the type is suppressed (P3.2).
 */
interface DiaryLedgerRepository {
  fun find(lineId: String): DiaryLedgerEntry?
  fun findByRecord(memoryDay: String, recordId: String): DiaryLedgerEntry?
  fun recordWritten(lineId: String, memoryDay: String, memoryRecordId: String, typeKey: String = "")

  /**
   * Tombstone a line (also works for a line never seen). A first-time delete of a line that has a type key adds one
   * strike to that type; pass [countsAsStrike] = false when the user replaces the line by editing it, not rejects it.
   */
  fun markDeleted(lineId: String, countsAsStrike: Boolean = true)

  /** Undo a delete: clears the tombstone, points the line at its re-created record, returns the strike. False if not deleted. */
  fun restore(lineId: String, memoryDay: String, memoryRecordId: String): Boolean

  /** True once the type has [DiaryThresholds.SUPPRESS_AFTER_DELETES] strikes; the composer then skips it. */
  fun isSuppressed(typeKey: String): Boolean

  fun suppressedTypes(): List<SuppressedLineType>

  /** Settings: let the diary write this type again (strikes reset to zero). */
  fun unsuppress(typeKey: String)
}
