package com.bbpillow.haku.thinking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ModelWarmthTest {
  private class Log { val steps = mutableListOf<String>() }

  private fun warmth(scope: CoroutineScope, log: Log) =
    ModelWarmth(scope, IDLE, warmUp = { log.steps += "up" }, coolDown = { log.steps += "down" })

  @Test
  fun opensOnceAndCoolsAfterIdle() = runTest {
    val log = Log()
    val w = warmth(backgroundScope, log)
    w.open(); w.open()
    runCurrent()
    assertEquals(listOf("up"), log.steps)
    advanceTimeBy(IDLE - 1); runCurrent()
    assertEquals(listOf("up"), log.steps)
    advanceTimeBy(2); runCurrent()
    assertEquals(listOf("up", "down"), log.steps)
  }

  @Test
  fun questionRestartsTheTimerWhenItEnds() = runTest {
    val log = Log()
    val w = warmth(backgroundScope, log)
    w.open(); runCurrent()
    advanceTimeBy(IDLE - 10)
    val answer = CompletableDeferred<Unit>()
    backgroundScope.launch { w.use { answer.await() } }
    advanceTimeBy(IDLE * 3); runCurrent() // a long question: never cooled under it
    assertEquals(listOf("up"), log.steps)
    answer.complete(Unit); runCurrent()
    advanceTimeBy(IDLE - 1); runCurrent()
    assertEquals(listOf("up"), log.steps)
    advanceTimeBy(2); runCurrent()
    assertEquals(listOf("up", "down"), log.steps)
  }

  @Test
  fun stopMidQuestionWaitsForItThenCools() = runTest {
    val log = Log()
    val w = warmth(backgroundScope, log)
    w.open(); runCurrent()
    val answer = CompletableDeferred<Unit>()
    backgroundScope.launch { w.use { answer.await() } }
    runCurrent()
    w.stop(); runCurrent()
    assertEquals(listOf("up"), log.steps)
    answer.complete(Unit); runCurrent()
    assertEquals(listOf("up", "down"), log.steps)
  }

  @Test
  fun stopWhenIdleCoolsNowAndQuestionReWarms() = runTest {
    val log = Log()
    val w = warmth(backgroundScope, log)
    w.open(); runCurrent()
    w.stop(); runCurrent()
    w.stop(); runCurrent() // already cold: no second coolDown
    assertEquals(listOf("up", "down"), log.steps)
    w.use { }
    runCurrent()
    assertEquals(listOf("up", "down", "up"), log.steps)
    advanceTimeBy(IDLE + 1); runCurrent()
    assertEquals(listOf("up", "down", "up", "down"), log.steps)
  }

  @Test
  fun reopenAfterStopCancelsThePendingStop() = runTest {
    val log = Log()
    val w = warmth(backgroundScope, log)
    w.open(); runCurrent()
    val answer = CompletableDeferred<Unit>()
    backgroundScope.launch { w.use { answer.await() } }
    runCurrent()
    w.stop(); w.open(); runCurrent() // background then back while answering
    answer.complete(Unit); runCurrent()
    assertEquals(listOf("up"), log.steps)
  }

  @Test
  fun failedWarmUpStillCoolsAndKeepsRunning() = runTest {
    val log = Log()
    val w = ModelWarmth(backgroundScope, IDLE, warmUp = { log.steps += "up"; error("no gpu") }, coolDown = { log.steps += "down" })
    w.open(); runCurrent()
    w.stop(); runCurrent()
    w.open(); runCurrent()
    assertEquals(listOf("up", "down", "up"), log.steps)
  }

  @Test
  fun questionWaitsForTheRunningWarmUpEvenIfItFails() = runTest {
    val log = Log()
    val gate = CompletableDeferred<Unit>()
    val w = ModelWarmth(backgroundScope, IDLE, warmUp = { log.steps += "up"; gate.await(); error("no gpu") }, coolDown = { log.steps += "down" })
    w.open(); runCurrent()
    backgroundScope.launch { w.use { log.steps += "ask" } }
    runCurrent()
    assertEquals(listOf("up"), log.steps) // the question's own load must not race the warm-up's
    gate.complete(Unit); runCurrent()
    assertEquals(listOf("up", "ask"), log.steps)
  }

  private companion object {
    const val IDLE = 60_000L
  }
}
