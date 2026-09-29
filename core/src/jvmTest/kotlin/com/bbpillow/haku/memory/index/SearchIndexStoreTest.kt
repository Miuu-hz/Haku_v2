package com.bbpillow.haku.memory.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.WordSegmenter
import com.bbpillow.haku.language.WordToken
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic stand-in for ICU: greedy longest match over a fixed word list; unknown characters form one word. */
class DictionarySegmenter(private val words: Set<String>) : WordSegmenter {
  private val longest = words.maxOfOrNull { it.length } ?: 1

  override fun segment(text: String, languageCode: String): List<WordToken> {
    val out = mutableListOf<WordToken>()
    var i = 0
    var unknownStart = -1
    fun flushUnknown(end: Int) {
      if (unknownStart >= 0) out += WordToken(text.substring(unknownStart, end), unknownStart, end)
      unknownStart = -1
    }
    while (i < text.length) {
      val len = (minOf(longest, text.length - i) downTo 1).firstOrNull { text.substring(i, i + it) in words }
      if (len == null) {
        if (unknownStart < 0) unknownStart = i
        i++
      } else {
        flushUnknown(i)
        out += WordToken(text.substring(i, i + len), i, i + len)
        i += len
      }
    }
    flushUnknown(text.length)
    return out
  }
}

class SearchIndexStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun store(words: Set<String>, synonyms: SynonymTable? = null): SearchIndexStore {
    val db = BundledSQLiteDriver().open(":memory:").also(opened::add)
    return SearchIndexStore(db, TextPipeline(DictionarySegmenter(words)), synonyms)
  }

  private val words = setOf("วันนี้", "ไป", "กิน", "ข้าวมัน", "ข้าว", "ที่", "ร้าน", "กาแฟ", "ประชุม", "กับ", "ทีม", "ครั้ง", "ทาน", "บ้าน")

  private fun ids(hits: List<SearchHitOutput>) = hits.map { it.id }

  @Test fun fts5IsCompiledIn() {
    val db = BundledSQLiteDriver().open(":memory:").also(opened::add)
    val used = db.prepare("SELECT sqlite_compileoption_used('ENABLE_FTS5')").use { it.step(); it.getLong(0) }
    assertEquals(1L, used)
  }

  @Test fun findsThaiWordInMiddleOfSentence() {
    val s = store(words)
    s.index(IndexRecordInput("a", "วันนี้ไปกินข้าวที่บ้าน"))
    s.index(IndexRecordInput("b", "ประชุมกับทีม"))
    val hits = s.search(SearchQueryInput("กิน"))
    assertEquals(listOf("a"), ids(hits))
    assertEquals(MatchKind.WORDS, hits.single().kind)
  }

  @Test fun findsThaiMixedWithEnglishAndIsCaseInsensitive() {
    val s = store(words)
    s.index(IndexRecordInput("a", "ประชุมกับทีม Starbucks"))
    s.index(IndexRecordInput("b", "ไปร้านกาแฟ"))
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("starbucks"))))
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("ประชุม Starbucks"))))
  }

  @Test fun thaiDigitsMatchArabicDigits() {
    val s = store(words)
    s.index(IndexRecordInput("a", "ไป ๓ ครั้ง"))
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("3"))))
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("๓"))))
  }

  @Test fun synonymExpansionFindsRecordWithOtherWord() {
    val s = store(words, SynonymTable(listOf(listOf("กิน", "ทาน"))))
    s.index(IndexRecordInput("a", "วันนี้ทานข้าวที่บ้าน"))
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("กิน"))))
    assertTrue(s.search(SearchQueryInput("กิน", expandSynonyms = false)).isEmpty())
  }

  @Test fun exactSubstringFallbackWhenWordsDoNotMatch() {
    val s = store(words)
    s.index(IndexRecordInput("a", "กินข้าวมันไก่"))
    // "มัน" is not a word of the record (ข้าวมัน is one), so FTS is empty and the substring pass finds it.
    val hits = s.search(SearchQueryInput("มัน"))
    assertEquals(listOf("a"), ids(hits))
    assertEquals(MatchKind.SUBSTRING, hits.single().kind)
  }

  @Test fun returnsOriginalTextExactlyAsWritten() {
    val s = store(words)
    val original = "วันนี้ไป ๓ ครั้ง​ ที่บ้านนนนนน Café"
    s.index(IndexRecordInput("a", original))
    assertEquals(original, s.search(SearchQueryInput("ครั้ง")).single().text)
  }

  @Test fun removeDeletesFromBothTables() {
    val s = store(words)
    s.index(IndexRecordInput("a", "ไปกินข้าว"))
    assertTrue(s.remove("a"))
    assertEquals(0, s.count())
    assertTrue(s.search(SearchQueryInput("กิน")).isEmpty())
    assertTrue(!s.remove("a"))
  }

  @Test fun indexingSameIdReplacesOldText() {
    val s = store(words)
    s.index(IndexRecordInput("a", "ไปกินข้าว"))
    s.index(IndexRecordInput("a", "ประชุมกับทีม"))
    assertEquals(1, s.count())
    assertTrue(s.search(SearchQueryInput("กิน")).isEmpty())
    assertEquals(listOf("a"), ids(s.search(SearchQueryInput("ทีม"))))
  }

  @Test fun rankingPutsMoreRelevantRecordFirstAndLimitApplies() {
    val s = store(words)
    s.index(IndexRecordInput("many", "กาแฟ กาแฟ กาแฟ"))
    s.index(IndexRecordInput("one", "ไปร้านกาแฟที่บ้านวันนี้กินข้าว"))
    assertEquals(listOf("many", "one"), ids(s.search(SearchQueryInput("กาแฟ"))))
    assertEquals(1, s.search(SearchQueryInput("กาแฟ", limit = 1)).size)
  }

  @Test fun blankQueryAndQuotesAreSafe() {
    val s = store(words)
    s.index(IndexRecordInput("a", "ไปกินข้าว"))
    assertTrue(s.search(SearchQueryInput("   ")).isEmpty())
    assertTrue(s.search(SearchQueryInput("\"OR\" AND * ( ' ;")).isEmpty())
  }
}
