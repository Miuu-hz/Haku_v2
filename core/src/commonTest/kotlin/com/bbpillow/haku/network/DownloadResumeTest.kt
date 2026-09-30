package com.bbpillow.haku.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadResumeTest {
  private fun decide(have: Long, status: Int, length: Long = -1, range: String? = null) =
    DownloadResume.decide(DownloadResponseInput(have, status, length, range))

  @Test
  fun freshDownloadWritesFromZeroWithTheAnnouncedSize() {
    assertEquals(ResumeDecision.Write(0, 1000), decide(0, 200, 1000))
  }

  @Test
  fun serverIgnoringRangeRestartsFromZero() {
    assertEquals(ResumeDecision.Write(0, 1000), decide(400, 200, 1000))
  }

  @Test
  fun partialContentAtOurOffsetAppends() {
    assertEquals(ResumeDecision.Write(400, 1000), decide(400, 206, 600, "bytes 400-999/1000"))
  }

  @Test
  fun partialContentWithUnknownTotalStillAppends() {
    assertEquals(ResumeDecision.Write(400, null), decide(400, 206, 600, "bytes 400-999/*"))
  }

  @Test
  fun partialContentAtAnotherOffsetStartsOver() {
    assertEquals(ResumeDecision.StartOver, decide(400, 206, 500, "bytes 500-999/1000"))
    assertEquals(ResumeDecision.StartOver, decide(400, 206, 500, null))
  }

  @Test
  fun rangePastTheEndIsCompleteOnlyWhenSizesMatch() {
    assertEquals(ResumeDecision.Complete, decide(1000, 416, range = "bytes */1000"))
    assertEquals(ResumeDecision.StartOver, decide(1200, 416, range = "bytes */1000"))
    assertEquals(ResumeDecision.StartOver, decide(1000, 416))
  }

  @Test
  fun otherStatusesFail() {
    assertEquals(ResumeDecision.Failed(403), decide(0, 403))
    assertEquals(ResumeDecision.Failed(500), decide(10, 500))
  }

  @Test
  fun fractionAndCompletion() {
    assertEquals(0f, DownloadResume.fraction(10, null))
    assertEquals(0.5f, DownloadResume.fraction(500, 1000))
    assertEquals(1f, DownloadResume.fraction(1500, 1000))
    assertTrue(DownloadResume.isComplete(1000, 1000))
    assertFalse(DownloadResume.isComplete(999, 1000))
    assertFalse(DownloadResume.isComplete(1000, null))
  }
}
