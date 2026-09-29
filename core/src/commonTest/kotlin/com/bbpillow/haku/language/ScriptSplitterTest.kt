package com.bbpillow.haku.language

import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptSplitterTest {
  private fun runs(text: String) = ScriptSplitter.split(text).map { it.script to text.substring(it.start, it.end) }

  @Test
  fun splitsThaiMixedWithLatin() {
    assertEquals(
      listOf(
        Script.THAI to "เดี๋ยว", Script.SPACE to " ", Script.LATIN to "confirm",
        Script.SPACE to " ", Script.THAI to "ให้นะ",
      ),
      runs("เดี๋ยว confirm ให้นะ"),
    )
  }

  @Test
  fun splitsDigitsAndPunctuation() {
    assertEquals(
      listOf(Script.LATIN to "meeting", Script.THAI to "บ่าย", Script.DIGIT to "2", Script.PUNCTUATION to "!?"),
      runs("meetingบ่าย2!?"),
    )
  }

  @Test
  fun emptyTextHasNoRuns() = assertEquals(emptyList(), ScriptSplitter.split(""))

  @Test
  fun runsCoverTheWholeText() {
    val text = "ซื้อ milk 2 ขวด, แล้วโทร 081-234"
    val runs = ScriptSplitter.split(text)
    assertEquals(0, runs.first().start)
    assertEquals(text.length, runs.last().end)
    runs.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
  }

  @Test
  fun otherScriptsAreSeparateFromLatin() {
    assertEquals(listOf(Script.OTHER to "日本語", Script.LATIN to "ok"), runs("日本語ok"))
  }
}
