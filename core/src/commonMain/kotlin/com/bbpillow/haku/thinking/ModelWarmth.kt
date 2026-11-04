package com.bbpillow.haku.thinking

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 5ข: keeps the chat's models loaded while the chat is in use, never for good. [open] (per the load policy) and [use] (a
 * question) run [warmUp]; [stop] (app in background, memory trim, policy) and [idleMillis] with no question run
 * [coolDown]. A stop or the timer never cools mid-question: a stop that arrives then waits for the last [use] to end.
 * Events run one at a time on [scope] in call order, so [warmUp]/[coolDown] never overlap and need no lock of their own.
 * [use] runs its block only after the warm-up it triggered or queued behind has finished (or failed): the block's own
 * model loads must not race [warmUp]'s (LiteRT hangs when the embedder opens while an LLM engine is open).
 */
class ModelWarmth(
  scope: CoroutineScope,
  private val idleMillis: () -> Long,
  private val warmUp: suspend () -> Unit,
  private val coolDown: suspend () -> Unit,
) {
  private sealed interface Event {
    data object Open : Event
    data class Begin(val warmed: CompletableDeferred<Unit>) : Event
    data object End : Event
    data object Stop : Event
    data class Idle(val generation: Int) : Event
  }

  private val events = Channel<Event>(Channel.UNLIMITED)

  /** True from the start of [warmUp] until [coolDown] ran. Read from any thread. */
  @Volatile var isWarm: Boolean = false
    private set

  init {
    scope.launch {
      var busy = 0
      var stopWhenDone = false
      var generation = 0
      var timer: Job? = null
      fun restartTimer() {
        timer?.cancel()
        val g = ++generation
        timer = scope.launch { delay(idleMillis()); events.send(Event.Idle(g)) }
      }
      suspend fun warm() {
        if (isWarm) return
        isWarm = true // before warmUp: a partial warm-up still gets its coolDown
        guarded(warmUp)
      }
      suspend fun cool() {
        timer?.cancel()
        generation++
        stopWhenDone = false
        if (!isWarm) return
        guarded(coolDown)
        isWarm = false
      }
      for (e in events) when (e) {
        Event.Open -> { stopWhenDone = false; warm(); if (busy == 0) restartTimer() }
        is Event.Begin -> { busy++; timer?.cancel(); generation++; try { warm() } finally { e.warmed.complete(Unit) } }
        Event.End -> { busy = maxOf(0, busy - 1); if (busy == 0) { if (stopWhenDone) cool() else restartTimer() } }
        Event.Stop -> if (busy > 0) stopWhenDone = true else cool()
        is Event.Idle -> if (e.generation == generation && busy == 0) cool()
      }
    }
  }

  fun open() { events.trySend(Event.Open) }

  fun stop() { events.trySend(Event.Stop) }

  /**
   * One question: warms if cold (the caller's own load then shares what this opens) and holds off cooling until done.
   * Waits for the warm-up to finish first; the loop handles Begin after any warm-up already running.
   */
  suspend fun <T> use(block: suspend () -> T): T {
    val warmed = CompletableDeferred<Unit>()
    events.trySend(Event.Begin(warmed))
    try {
      warmed.await()
      return block()
    } finally {
      events.trySend(Event.End)
    }
  }

  private suspend fun guarded(step: suspend () -> Unit) = try {
    step()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    // the caller's steps log their own failures; a throw here must not stop the event loop
  }
}
