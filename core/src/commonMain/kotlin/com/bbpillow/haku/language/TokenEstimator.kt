package com.bbpillow.haku.language

import kotlin.math.ceil

/**
 * Token estimate without a tokenizer (doc 03 s9: Thai costs far more per character than English).
 * Deliberately pessimistic so a budget is never exceeded; calibrate the weights once the real tokenizer is measured.
 * Thai letters/marks ~0.6 token each, Latin letters/digits ~0.25, spaces/punctuation ~0.15, anything else (emoji, CJK) 1.
 */
object TokenEstimator {
  const val THAI = 0.6
  const val LATIN = 0.25
  const val SEPARATOR = 0.15
  const val OTHER = 1.0

  fun estimate(text: String): Int {
    var sum = 0.0
    var i = 0
    while (i < text.length) {
      val c = text[i]
      sum += when {
        c in '฀'..'๿' -> THAI
        c.isLetterOrDigit() && c.code < 0x250 -> LATIN
        c.isWhitespace() || c.code < 0x80 -> SEPARATOR
        else -> OTHER
      }
      i += if (c.isHighSurrogate()) 2 else 1
    }
    return ceil(sum).toInt()
  }
}
