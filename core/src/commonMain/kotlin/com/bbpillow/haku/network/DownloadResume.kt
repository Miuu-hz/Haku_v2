package com.bbpillow.haku.network

/** What to do with a partial download after asking the server for `Range: bytes=<have>-` (P3.6 model download). */
sealed interface ResumeDecision {
  /** Write the body from [fromByte] (0 = truncate the partial file first). [totalBytes] null = server did not say. */
  data class Write(val fromByte: Long, val totalBytes: Long?) : ResumeDecision

  /** The partial file already holds the whole thing. */
  data object Complete : ResumeDecision

  /** The partial file does not match the server's copy: delete it and ask again from 0. */
  data object StartOver : ResumeDecision

  data class Failed(val status: Int) : ResumeDecision
}

/** Server answer to a (possibly ranged) GET, as plain values so the rules run without a network. */
data class DownloadResponseInput(val haveBytes: Long, val status: Int, val contentLength: Long, val contentRange: String?)

object DownloadResume {
  private val rangeRule = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
  private val unsatisfiedRule = Regex("""bytes \*/(\d+)""")

  fun decide(input: DownloadResponseInput): ResumeDecision = when (input.status) {
    // no Range sent, or the server ignored it: the body is the whole file
    200 -> ResumeDecision.Write(0, input.contentLength.takeIf { it >= 0 })
    206 -> rangeRule.matchEntire(input.contentRange?.trim().orEmpty())?.let { m ->
      val (start, _, total) = m.destructured
      if (start.toLong() == input.haveBytes) ResumeDecision.Write(input.haveBytes, total.toLongOrNull()) else ResumeDecision.StartOver
    } ?: ResumeDecision.StartOver
    // asked for bytes past the end: done if our size is the file size, else our copy is from another file
    416 -> unsatisfiedRule.matchEntire(input.contentRange?.trim().orEmpty())
      ?.let { if (it.groupValues[1].toLong() == input.haveBytes && input.haveBytes > 0) ResumeDecision.Complete else null }
      ?: ResumeDecision.StartOver
    else -> ResumeDecision.Failed(input.status)
  }

  /** 0..1; 0 while the size is unknown. */
  fun fraction(haveBytes: Long, totalBytes: Long?): Float =
    if (totalBytes == null || totalBytes <= 0) 0f else (haveBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

  /** The finished file is kept only when its size is the size the server announced. ponytail: no hash check, HF gives none in the header. */
  fun isComplete(haveBytes: Long, totalBytes: Long?): Boolean = totalBytes != null && haveBytes == totalBytes
}
