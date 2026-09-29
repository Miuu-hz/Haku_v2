package com.bbpillow.haku.language

import kotlin.test.Test
import kotlin.test.assertEquals

/** Greedy longest-match over a tiny dictionary; stands in for the OS word breaker in commonTest. */
private class DictionarySegmenter(private val words: Set<String>) : WordSegmenter {
  var calls = mutableListOf<Pair<String, String>>()

  override fun segment(text: String, languageCode: String): List<WordToken> {
    calls += text to languageCode
    val out = mutableListOf<WordToken>()
    var i = 0
    while (i < text.length) {
      val end = (text.length downTo i + 1).firstOrNull { text.substring(i, it) in words } ?: (i + 1)
      out += WordToken(text.substring(i, end), i, end)
      i = end
    }
    return out
  }
}

class TextPipelineTest {
  private val dict = setOf("เดี๋ยว", "ให้", "นะ", "เร็ว", "ๆ", "ไป", "ซื้อ", "ของ", "ตอน", "บ่าย", "สอง", "ๆ")
  private val segmenter = DictionarySegmenter(dict)
  private val pipeline = TextPipeline(segmenter)

  private fun words(text: String, lang: String = "th") = pipeline.process(TextInput(text, lang)).tokens.map { it.text }

  @Test
  fun mixedThaiEnglishSegmentsOnlyThaiRunsThroughTheSegmenter() {
    assertEquals(listOf("เดี๋ยว", "confirm", "ให้", "นะ"), words("เดี๋ยว confirm ให้นะ"))
    assertEquals(listOf("เดี๋ยว" to "th", "ให้นะ" to "th"), segmenter.calls)
  }

  @Test
  fun offsetsPointIntoTheNormalizedText() {
    val out = pipeline.process(TextInput("ซื้อ milk", "th"))
    assertEquals(listOf(WordToken("ซื้อ", 0, 4), WordToken("milk", 5, 9)), out.tokens)
    assertEquals("milk", out.normalized.substring(5, 9))
  }

  @Test
  fun maiYamokIsExpandedAfterSegmentation() {
    assertEquals(listOf("ไป", "เร็ว", "เร็ว"), words("ไปเร็วๆ"))
    assertEquals(listOf("ไป", "เร็ว", "เร็ว"), words("ไปเร็ว ๆ"))
  }

  @Test
  fun originalIsKeptAndSegmentedTextIsSpaceJoined() {
    val out = pipeline.process(TextInput("ตอน๒บ่ายสอง", "th"))
    assertEquals("ตอน๒บ่ายสอง", out.original)
    assertEquals("ตอน 2 บ่าย สอง", out.segmented)
  }

  @Test
  fun equalLookingInputsGiveTheSameIndexText() {
    val a = pipeline.process(TextInput("ไป\u200Bซื้อของ", "th")).segmented
    val b = pipeline.process(TextInput("ไปซื้อของ", "th")).segmented
    assertEquals(b, a)
    assertEquals("ไป ซื้อ ของ", a)
  }

  @Test
  fun emptyAndPunctuationOnlyTextHaveNoTokens() {
    assertEquals(emptyList(), words(""))
    assertEquals(emptyList(), words(" ?! ... "))
  }

  @Test
  fun otherLanguageUsesNoThaiRules() {
    assertEquals(listOf("ๆ"), words("ๆ", "xx"))
  }
}
