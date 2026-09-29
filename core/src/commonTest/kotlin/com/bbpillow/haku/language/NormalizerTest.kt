package com.bbpillow.haku.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test suite #1 (doc 03 section 10): visually equal texts must normalize to the same value. */
private val ZWSP = 0x200B.toChar().toString()

class NormalizerTest {
  private fun norm(text: String) = Normalizer.normalize(TextInput(text, "th")).normalized

  // Words with vowel mark + tone mark, in canonical order (vowel, then tone).
  private val vowelToneWords = listOf(
    "ที่", "นี้", "ผู้", "คุ้ม", "ชั่ว", "ปี้", "สู่", "กิ๊ก", "เขี่ย", "ตื่น", "เรื่อง", "อื่น",
    "ขึ้น", "ซึ้ง", "หนี้", "สี่", "ยี่", "พื้น", "ลื่น", "จั๊ก", "ฟุ้ง", "มั่น", "ดู๋", "กี๋",
  )

  private fun swapVowelTone(word: String): String {
    val chars = word.toCharArray()
    val i = chars.indices.first { chars[it] in '\u0E48'..'\u0E4B' }
    chars[i] = chars[i - 1].also { chars[i - 1] = chars[i] }
    return String(chars)
  }

  /** Each pair: (a differently-encoded text, the text it must equal after normalization). */
  private fun generatedPairs(): List<Pair<String, String>> = buildList {
    for (w in vowelToneWords) {
      add(swapVowelTone(w) to w)                       // tone typed before vowel
      add("\u200B$w\u200B" to w)                        // zero-width space
      add(w.map { "$it" + ZWSP }.joinToString("") to w)  // zero-width space after every char
    }
    for (w in listOf("น้ำ", "ค่ำ", "ล้ำ", "ร่ำ", "ช้ำ")) {
      val (base, tone) = w[0] to w[1]
      add("$base\u0E4D$tone\u0E32" to w)               // nikhahit + tone + sara aa
      add("$base$tone\u0E4D\u0E32" to w)               // tone typed before nikhahit
      add("$base\u0E33$tone" to w)                      // tone after sara am
    }
    for (w in listOf("ทำ", "คำ", "นำ", "กำ", "ดำ")) add("${w[0]}\u0E4D\u0E32" to w)  // nikhahit + sara aa
  }

  private val explicitPairs = listOf(
    "๐๑๒๓๔๕๖๗๘๙" to "0123456789",
    "ราคา ๑๒๓ บาท" to "ราคา 123 บาท",
    "๒๕๖๙" to "2569",
    "เดี๋ยว ๓ โมง" to "เดี๋ยว 3 โมง",
    "มากกกกก" to "มากก",
    "มากกก" to "มากก",
    "ดีมากกกกกกก" to "ดีมากก",
    "555555" to "555555",
    "ฮ่าๆๆๆๆ" to "ฮ่าๆๆ",
    "ๆ" to "ๆ",
    "เร็ว ๆ" to "เร็วๆ",
    "เร็ว   ๆ" to "เร็วๆ",
    "เเก้ว" to "แก้ว",
    "\uFEFFสวัสดี" to "สวัสดี",
    "สวัส\u200Bดี\u200Bครับ" to "สวัสดีครับ",
    "สวัส\u2060ดี" to "สวัสดี",
    "ดีใจ\u200Dมาก" to "ดีใจมาก",
    "meet\u200Bing" to "meeting",
    "Cafe\u0301" to "Caf\u00E9",                       // NFC for non-Thai text
    "e\u0301" to "\u00E9",
    "coooool" to "cool",
    "" to "",
  )

  @Test
  fun everyPairNormalizesToTheSameForm() {
    val pairs = generatedPairs() + explicitPairs
    val failures = pairs.filter { (a, b) -> norm(a) != norm(b) }
    assertTrue(pairs.size >= 100, "suite must hold at least 100 pairs, has ${pairs.size}")
    assertEquals(emptyList(), failures.map { "${it.first} -> ${norm(it.first)} != ${norm(it.second)}" })
  }

  @Test
  fun explicitExpectedValues() {
    assertEquals("น้ำ", norm("น\u0E4D\u0E49\u0E32"))
    assertEquals("ที่", norm("ท\u0E48\u0E35"))
    assertEquals("cool", norm("coooool"))
  }

  @Test
  fun originalIsNeverModified() {
    val raw = "ราคา ๑๒๓ มากกกกก\u200B"
    val out = Normalizer.normalize(TextInput(raw, "th"))
    assertEquals(raw, out.original)
    assertEquals("ราคา 123 มากก", out.normalized)
  }

  @Test
  fun digitsAreNotCollapsed() {
    assertEquals("10000", norm("10000"))
    assertEquals("1000", norm("๑๐๐๐"))
  }

  @Test
  fun unknownLanguageSkipsThaiRulesButKeepsCoreRules() {
    val out = Normalizer.normalize(TextInput("๑๒ aaaa\u200B", "en")).normalized
    assertEquals("๑๒ aa", out)
  }

  @Test
  fun normalizationIsIdempotent() {
    for ((a, _) in generatedPairs() + explicitPairs) assertEquals(norm(a), norm(norm(a)))
  }

  @Test
  fun synonymTermsAreAlreadyNormalized() {
    val bad = thaiSynonyms.terms.filter { norm(it) != it }
    assertEquals(emptyList(), bad)
  }
}
