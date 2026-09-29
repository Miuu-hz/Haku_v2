package com.bbpillow.haku.capture.diary

/**
 * Remembers which diary lines were written and which the user deleted (mirrors CaptureEventStore's tombstones).
 * A deleted line stays here forever, so re-composing the day never brings it back.
 */
interface DiaryLedgerRepository {
  fun find(lineId: String): DiaryLedgerEntry?
  fun findByRecord(memoryDay: String, recordId: String): DiaryLedgerEntry?
  fun recordWritten(lineId: String, memoryDay: String, memoryRecordId: String)

  /** Tombstone a line (also works for a line never seen). */
  fun markDeleted(lineId: String)
}
