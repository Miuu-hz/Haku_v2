package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.Provenance

/**
 * Stage 1 decision (pure): which records in one group (one day file, or the intentions file) are duplicates.
 * Near-identical = same normalized text. A user-typed original is only ever removed as an exact duplicate
 * (identical raw text) of an earlier user-typed one; system-made (EXTERNAL/SYSTEM) copies lose to a user-typed twin, else the earliest stays.
 */
internal fun tidyGroup(level: MemoryLevel, day: String, records: List<MemoryRecord>, normalize: (String) -> String): List<TidiedRecord> {
  val out = mutableListOf<TidiedRecord>()
  for (same in records.groupBy { normalize(it.text) }.values.filter { it.size > 1 }) {
    val users = same.filter { it.provenance == Provenance.USER_TYPED }
    // INFERRED_FROM_USER_DATA = diary lines: the DiaryLedger points at them by id, so they are never tidied (only kept)
    val diary = same.filter { it.provenance == Provenance.INFERRED_FROM_USER_DATA }
    val others = same.filter { it.provenance != Provenance.USER_TYPED && it.provenance != Provenance.INFERRED_FROM_USER_DATA }
    val keeper = users.firstOrNull() ?: diary.firstOrNull() ?: others.first()
    val seenRaw = mutableMapOf<String, MemoryRecord>()
    for (u in users) {
      val first = seenRaw.getOrPut(u.text) { u }
      if (first !== u) out += TidiedRecord(level, day, u.id, u.text, first.id, exact = true)
    }
    for (o in others) if (o !== keeper) out += TidiedRecord(level, day, o.id, o.text, keeper.id, exact = o.text == keeper.text)
  }
  return out
}
