package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.action.AuditRepository
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.DiaryLedgerRepository
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.network.ConnectionRepository
import com.bbpillow.haku.surface.chat.ChatHistoryStore
import com.bbpillow.haku.surface.onboarding.ProfileRepository
import com.bbpillow.haku.thinking.summary.EveningSummaryRepository

/**
 * "Forget everything" (PDPA s.33). Order matters: capture events go first (tombstoned, so a calendar re-read cannot
 * refill them), then what was generated from them (places, ledger, summaries), then the memory files and index.
 * The onboarding profile keeps its choices (particle, address, model, lock) so the app still works, but the nickname
 * is personal data and is erased. The dreams level (reports and Dream's ledger day) goes with memory.deleteAll().
 * The connection log (E3) goes too: its rows say when the user used the app.
 * Runs on the capture thread like every store call.
 */
class ForgetEverything(
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val ledger: DiaryLedgerRepository,
  private val summaries: EveningSummaryRepository,
  private val memory: MemoryRepository,
  private val profile: ProfileRepository,
  private val connections: ConnectionRepository,
  /** P4.1 action audit log; null only in tests that predate it. */
  private val audit: AuditRepository? = null,
  /** P3.8 saved chats; null only in tests that predate it. */
  private val chats: ChatHistoryStore? = null,
) {
  /** Returns how many capture events were erased. */
  fun run(): Int {
    val erased = events.forgetAll()
    places.deleteAll()
    ledger.clear()
    summaries.clear()
    memory.deleteAll()
    connections.clear()
    audit?.clear()
    chats?.clear()
    val p = profile.load()
    if (p.style.nickname.isNotEmpty()) profile.save(p.copy(style = p.style.copy(nickname = "")))
    return erased
  }
}
