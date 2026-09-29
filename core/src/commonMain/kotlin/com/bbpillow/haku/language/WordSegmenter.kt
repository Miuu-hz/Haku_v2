package com.bbpillow.haku.language

/** OS-backed word breaker. Returns words only (no spaces/punctuation), offsets into [text]. */
interface WordSegmenter {
  fun segment(text: String, languageCode: String): List<WordToken>
}
