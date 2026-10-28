package com.bbpillow.haku.surface.intent

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.thinking.intent.IntentService
import com.bbpillow.haku.thinking.intent.IntentionText

/** One row: [active] = can still fire. Older free-text intentions show with "ไม่มีเงื่อนไข" and are never fired. */
data class IntentItem(val id: String, val title: String, val detail: String, val active: Boolean)

data class IntentListUiState(val items: List<IntentItem>)

/** P5.3 "ความตั้งใจค้างไว้": view and cancel. Runs on the DB thread. */
class IntentListPresenter(private val intents: IntentService, private val tzOffsetMinutes: Int, private val now: () -> Long) {
  fun load(): IntentListUiState {
    val nowMs = now()
    val items = intents.all().map { (record, i) ->
      if (i == null) {
        IntentItem(record.id, record.text.lineSequence().first(), "ไม่มีเงื่อนไข · จดไว้ ${date(record.atEpochMs)}", active = false)
      } else {
        val state = when {
          i.fired >= i.maxFires -> "เตือนครบแล้ว"
          nowMs >= i.expiresAtMs -> "หมดอายุแล้ว"
          else -> "ถึง ${date(i.expiresAtMs)}"
        }
        IntentItem(i.id, "เตือน “${i.reminder}”", "${IntentionText.whenText(i)} · เตือนแล้ว ${i.fired}/${i.maxFires} · $state", i.isLive(nowMs))
      }
    }
    return IntentListUiState(items.sortedByDescending { it.active })
  }

  fun cancel(id: String): IntentListUiState {
    intents.cancel(id)
    return load()
  }

  private fun date(ms: Long) = ThaiDateFormat.shortDate(MemoryFormat.dayOf(ms + tzOffsetMinutes * 60_000L))
}
