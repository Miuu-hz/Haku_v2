package com.bbpillow.haku.platform

import android.icu.text.BreakIterator
import com.bbpillow.haku.language.WordSegmenter
import com.bbpillow.haku.language.WordToken
import java.util.Locale

/** OS word breaker (ICU dictionary-based, ships with Android). Language is a parameter, nothing Thai here. */
class IcuWordSegmenter : WordSegmenter {
  override fun segment(text: String, languageCode: String): List<WordToken> {
    val it = BreakIterator.getWordInstance(Locale.forLanguageTag(languageCode))
    it.setText(text)
    val tokens = mutableListOf<WordToken>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
      // rule status below WORD_NONE_LIMIT = spaces / punctuation, not a word
      if (it.ruleStatus >= BreakIterator.WORD_NONE_LIMIT) tokens += WordToken(text.substring(start, end), start, end)
      start = end
      end = it.next()
    }
    return tokens
  }
}
