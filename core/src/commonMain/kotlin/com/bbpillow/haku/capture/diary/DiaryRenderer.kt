package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat

/** Episodes to Thai lines. Pure; wording is in [DiaryTemplates]. */
object DiaryRenderer {
  private const val MIN_MS = 60_000L

  fun render(day: String, tzOffsetMinutes: Int, style: DiaryStyle, episodes: List<DiaryEpisode>): DiaryDayOutput {
    val hhmm = { utc: Long -> MemoryFormat.formatUtc(utc + tzOffsetMinutes * MIN_MS).substring(11, 16) }
    val lines = episodes.map { e ->
      val body = when (e) {
        is DiaryEpisode.Departure -> DiaryTemplates.departure(e.from)
        is DiaryEpisode.Arrival -> DiaryTemplates.arrival(e.at, e.travelMs, e.stayMs)
        is DiaryEpisode.Calendar -> DiaryTemplates.calendar(e.title, e.endUtcMs?.let(hhmm))
      }
      DiaryLineRecord(
        id = DiaryLineIds.of(e.kind, e.sourceEventIds, e.timeUtcMs),
        day = day,
        kind = e.kind,
        timeUtcMs = e.timeUtcMs,
        text = "${hhmm(e.timeUtcMs)} $body${style.particle.text}",
        sourceEventIds = e.sourceEventIds,
        typeKey = e.typeKey,
      )
    }
    return DiaryDayOutput(day, ThaiDateFormat.dayHeader(day), lines)
  }
}

/** Line ids from source events only (never text, place names or style), so they stay stable for delete-never-returns. */
object DiaryLineIds {
  private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
  private const val FNV_PRIME = 1099511628211L

  fun of(kind: DiaryLineKind, sourceEventIds: List<String>, timeUtcMs: Long): String {
    val key = kind.name + "|" + sourceEventIds.sorted().ifEmpty { listOf(timeUtcMs.toString()) }.joinToString("|")
    var h = FNV_OFFSET
    for (b in key.encodeToByteArray()) h = (h xor (b.toLong() and 0xff)) * FNV_PRIME
    return "dl-" + h.toULong().toString(16).padStart(16, '0')
  }
}
