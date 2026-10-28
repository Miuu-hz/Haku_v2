package com.bbpillow.haku.thinking.intent

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecognizer
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance

/**
 * P5.3 standing intentions kept in INTENTIONS.md through [MemoryRepository] (no table of their own). Adds, lists,
 * cancels, and on each new event fires the matching ones and writes their counters back. Not thread-safe (as memory).
 */
class IntentService(
  private val memory: MemoryRepository,
  private val places: PlaceRepository,
  pipeline: TextPipeline,
) {
  private val matcher = IntentMatcher(pipeline)

  /** Every intention record: parsed ones as [Intention], older free-text ones as null (shown, never fired). */
  fun all(): List<Pair<MemoryRecord, Intention?>> =
    memory.readIntentions().map { it to IntentionText.decode(it.id, it.text, it.atEpochMs) }

  /** Stores [input] as typed by the user. Null when its trigger has no real word to match on ("ถ้าไป ให้เตือน…"). */
  fun add(input: IntentionInput, nowMs: Long): Intention? {
    if (matcher.keywords(input.trigger).isEmpty()) return null
    val draft = Intention(
      id = "", trigger = input.trigger, reminder = input.reminder, scope = input.scope, maxFires = input.maxFires,
      cooldownMs = input.cooldownMs, expiresAtMs = nowMs + input.expiresInMs, createdAtMs = nowMs,
      placeId = if (input.scope == IntentScope.PLACE) pinPlace(input.trigger) else null,
    )
    val record = memory.addIntention(NoteInput(IntentionText.encode(draft), Provenance.USER_TYPED, nowMs))
    return draft.copy(id = record.id)
  }

  fun cancel(id: String): Boolean = memory.deleteIntention(id)

  /** New capture rows: arrivals at a confirmed place and appointments not over yet. */
  fun onCapture(events: List<CaptureEvent>, nowMs: Long): List<IntentFireOutput> {
    val confirmed by lazy { places.list().filter { it.confirmed } }
    return events.mapNotNull { e ->
      when (val s = e.source) {
        CaptureSource.DwellStart -> e.place?.let { p ->
          confirmed.filter { PlaceRecognizer.distanceMeters(p.lat, p.lon, it.lat, it.lon) <= ARRIVAL_RADIUS_M }
            .minByOrNull { PlaceRecognizer.distanceMeters(p.lat, p.lon, it.lat, it.lon) }
            ?.let { IntentTriggerEvent(IntentScope.PLACE, nameOf(it), e.timeUtcMs, it.id) }
        }
        is CaptureSource.CalendarEntry ->
          IntentTriggerEvent(IntentScope.APPOINTMENT, s.title, nowMs).takeIf { (e.endUtcMs ?: e.timeUtcMs) >= nowMs }
        else -> null
      }
    }.flatMap(::fire)
  }

  /** A diary line the user typed. */
  fun onNote(text: String, nowMs: Long): List<IntentFireOutput> = fire(IntentTriggerEvent(IntentScope.NOTE, text, nowMs))

  fun fire(event: IntentTriggerEvent): List<IntentFireOutput> {
    val live = all().mapNotNull { it.second }
    return matcher.match(live, event).map { i ->
      val used = i.copy(fired = i.fired + 1, lastFiredAtMs = event.atMs)
      memory.updateIntention(i.id, IntentionText.encode(used))
      IntentFireOutput(i.id, "อย่าลืม: ${i.reminder}", "${IntentionText.whenText(i)} ตามที่คุณตั้งไว้")
    }
  }

  /** The one confirmed place whose name has all the trigger words: pin it, so a later rename still fires. */
  private fun pinPlace(trigger: String): String? {
    val want = matcher.keywords(trigger)
    return places.list().filter { it.confirmed && matcher.keywords(nameOf(it)).containsAll(want) }.singleOrNull()?.id
  }

  companion object {
    const val ARRIVAL_RADIUS_M = 150.0

    /** The words a place answers to: its label plus what it is ("ออฟฟิศสีลม ที่ทำงาน"). */
    fun nameOf(p: PlaceRecord): String = listOfNotNull(
      p.label,
      when (p.category) {
        PlaceCategory.HOME -> "บ้าน"
        PlaceCategory.WORK -> "ที่ทำงาน"
        PlaceCategory.OTHER -> null
      },
    ).joinToString(" ")
  }
}
