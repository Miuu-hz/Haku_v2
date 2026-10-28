package com.bbpillow.haku.thinking

import kotlin.math.sqrt

/**
 * On-device text to vector (ชิ้น ค, EmbeddingGemma). The semantic hook in ContextAssembler will rank memory lines by
 * [cosine] of these vectors once it is switched on. Adapters live in androidApp `platform`.
 */
interface TextEmbedder {
  /** Vector length; 0 until the first [embed]. */
  val dims: Int

  /** One vector per text, same order. Blocking native work is the implementation's job to keep off the caller's thread. */
  suspend fun embed(texts: List<String>): List<FloatArray>
}

/** Cosine similarity in [-1, 1]; 0 when either vector is all zeros. */
fun cosine(a: FloatArray, b: FloatArray): Float {
  require(a.size == b.size) { "dims differ: ${a.size} vs ${b.size}" }
  var dot = 0.0
  var na = 0.0
  var nb = 0.0
  for (i in a.indices) {
    dot += a[i] * b[i]
    na += a[i] * a[i]
    nb += b[i] * b[i]
  }
  return if (na == 0.0 || nb == 0.0) 0f else (dot / (sqrt(na) * sqrt(nb))).toFloat()
}
