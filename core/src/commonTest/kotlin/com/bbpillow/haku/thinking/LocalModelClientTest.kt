package com.bbpillow.haku.thinking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class LocalModelClientTest {
  private val config = ModelConfig(modelPath = "/m.litertlm", backend = ModelBackend.Cpu)
  private val input = GenerateInput("สวัสดี")

  private fun setup(
    chunks: List<String> = listOf("ส", "วัส", "ดี"),
    gap: Int = 100,
    loadMs: Int = 1500,
    failOnLoad: Throwable? = null,
    failAfter: Throwable? = null,
  ): Pair<LocalModelClient, FakeLocalModel> {
    val time = TestTimeSource()
    val fake = FakeLocalModel(time, chunks, loadMs.milliseconds, gap.milliseconds, failOnLoad, failAfter)
    return LocalModelClient(fake, time) to fake
  }

  private suspend fun LocalModelClient.metrics() =
    (generate(input).toList().last() as GenerateEvent.Finished).output.metrics

  @Test fun loadReportsElapsedTimeAndPassesConfig() = runTest {
    val (client, fake) = setup(loadMs = 1500)
    assertEquals(1500, client.load(config).loadMillis)
    assertEquals(config, fake.loadedConfig)
  }

  @Test fun loadFailurePropagates() = runTest {
    val (client, _) = setup(failOnLoad = IllegalStateException("bad file"))
    assertEquals("bad file", assertFailsWith<IllegalStateException> { client.load(config) }.message)
  }

  @Test fun streamsTokensInOrderThenFinishesWithFullText() = runTest {
    val (client, _) = setup()
    client.load(config)
    val events = client.generate(input).toList()
    assertEquals(listOf("ส", "วัส", "ดี"), events.filterIsInstance<GenerateEvent.Token>().map { it.text })
    val out = (events.last() as GenerateEvent.Finished).output
    assertEquals("สวัสดี", out.text)
    assertEquals(3, out.metrics.chunkCount)
    assertFalse(out.metrics.cancelled)
  }

  @Test fun ttftIsTimeToFirstNonEmptyChunk() = runTest {
    val (client, _) = setup(chunks = listOf("", "", "a", "b"))
    client.load(config)
    val m = client.metrics()
    assertEquals(300, m.timeToFirstTokenMillis)
    assertEquals(2, m.chunkCount)
    assertEquals(400, m.totalMillis)
  }

  @Test fun tokensPerSecondUsesDecodePhaseOnly() = runTest {
    val (client, _) = setup(chunks = List(5) { "x" })
    client.load(config)
    assertEquals(10.0, assertNotNull(client.metrics().tokensPerSecond), 1e-9)
  }

  @Test fun singleChunkHasNoTokensPerSecond() = runTest {
    val (client, _) = setup(chunks = listOf("x"))
    client.load(config)
    val m = client.metrics()
    assertNull(m.tokensPerSecond)
    assertEquals(100, m.timeToFirstTokenMillis)
  }

  @Test fun emptyOutputHasNoTtft() = runTest {
    val (client, _) = setup(chunks = emptyList())
    client.load(config)
    val out = (client.generate(input).toList().single() as GenerateEvent.Finished).output
    assertEquals("", out.text)
    assertNull(out.metrics.timeToFirstTokenMillis)
    assertEquals(0, out.metrics.chunkCount)
  }

  @Test fun cancelStopsStreamAndMarksMetrics() = runTest {
    val (client, fake) = setup(chunks = List(10) { "x" })
    client.load(config)
    val events = mutableListOf<GenerateEvent>()
    client.generate(input).collect {
      events += it
      if (events.size == 2) client.cancel()
    }
    assertTrue(fake.cancelled)
    val out = (events.last() as GenerateEvent.Finished).output
    assertTrue(out.metrics.cancelled)
    assertEquals(2, out.metrics.chunkCount)
  }

  @Test fun cancelBeforeFirstChunkMarksCancelled() = runTest {
    val (client, _) = setup(chunks = listOf("a", "b"))
    client.load(config)
    client.cancel()
    val m = client.metrics()
    assertTrue(m.cancelled)
    assertEquals(0, m.chunkCount)
  }

  @Test fun cancelFlagDoesNotLeakIntoNextGenerate() = runTest {
    val (client, _) = setup(chunks = listOf("a", "b"))
    client.load(config)
    client.cancel()
    assertTrue(client.metrics().cancelled)
    val next = client.metrics()
    assertFalse(next.cancelled)
    assertEquals(2, next.chunkCount)
  }

  @Test fun runtimeErrorSurfacesAfterPartialTokens() = runTest {
    val (client, _) = setup(chunks = listOf("a"), failAfter = RuntimeException("oom"))
    client.load(config)
    val seen = mutableListOf<GenerateEvent>()
    val e = assertFailsWith<RuntimeException> { client.generate(input).collect { seen += it } }
    assertEquals("oom", e.message)
    assertEquals(listOf<GenerateEvent>(GenerateEvent.Token("a")), seen)
  }

  @Test fun generateBeforeLoadFails() = runTest {
    val (client, _) = setup()
    assertFailsWith<IllegalStateException> { client.generate(input).toList() }
  }

  @Test fun unloadReleasesModel() = runTest {
    val (client, fake) = setup()
    client.load(config)
    client.unload()
    assertFalse(fake.loaded)
  }

  @Test fun collectorStoppingEarlyDoesNotEmitFinished() = runTest {
    val (client, _) = setup(chunks = List(5) { "x" })
    client.load(config)
    val events = client.generate(input).take(2).toList()
    assertTrue(events.all { it is GenerateEvent.Token })
  }
}
