package com.bbpillow.haku.surface.note

import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance

/** P3.5 one typed thought from the widget, the lock-screen box or a notification reply. */
data class QuickNoteInput(val text: String, val nowUtcMs: Long)

/**
 * P3.5 saves a quick thought as today's [Provenance.USER_TYPED] daily line, "HH:MM text" in local time like a timeline
 * edit. Write-only on purpose: it runs over the lock screen, so it never reads the diary back.
 */
class QuickNote(private val memory: MemoryRepository, private val tzOffsetMinutes: Int) {
  /** Null when there is nothing to save. */
  fun save(input: QuickNoteInput): MemoryRecord? {
    val text = clean(input.text) ?: return null
    val hhmm = MemoryFormat.formatUtc(input.nowUtcMs + tzOffsetMinutes * 60_000L).substring(11, 16)
    return memory.appendDaily(DailyEventInput("$hhmm $text", Provenance.USER_TYPED, input.nowUtcMs))
  }

  companion object {
    const val MAX_CHARS = 500

    /** One note = one line: runs of whitespace (line breaks too) become one space, capped at [MAX_CHARS]. Blank = null. */
    fun clean(raw: String): String? {
      val line = raw.split(SPACES).filter { it.isNotEmpty() }.joinToString(" ").take(MAX_CHARS)
      // a cut emoji would leave half a surrogate pair in the file
      return (if (line.lastOrNull()?.isHighSurrogate() == true) line.dropLast(1) else line).ifEmpty { null }
    }

    private val SPACES = Regex("\\s+")
  }
}
