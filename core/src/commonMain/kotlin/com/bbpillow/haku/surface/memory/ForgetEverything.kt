package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.DiaryLedgerRepository
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.thinking.summary.EveningSummaryRepository

/**
 * "Forget everything" (PDPA s.33). Order matters: capture events go first (tombstoned, so a calendar re-read cannot
 * refill them), then what was generated from them (places, ledger, summaries), then the memory files and index.
 * The onboarding profile (style, model, lock) is a setting, not remembered content, and stays.
 * Runs on the capture thread like every store call.
 */
class ForgetEverything(
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val ledger: DiaryLedgerRepository,
  private val summaries: EveningSummaryRepository,
  private val memory: MemoryRepository,
) {
  /** Returns how many capture events were erased. */
  fun run(): Int {
    val erased = events.forgetAll()
    places.deleteAll()
    ledger.clear()
    summaries.clear()
    memory.deleteAll()
    return erased
  }
}
