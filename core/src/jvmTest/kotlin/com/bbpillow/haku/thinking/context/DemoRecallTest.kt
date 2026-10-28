package com.bbpillow.haku.thinking.context

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.PlaceStore
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryLedgerStore
import com.bbpillow.haku.demo.DemoSeed
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.WordSegmenter
import com.bbpillow.haku.language.WordToken
import com.bbpillow.haku.language.thaiSynonyms
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.text.BreakIterator
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** The on-device eval's retrieval misses, replayed against the demo persona at the chat budget (900 tokens). */
class DemoRecallTest {
  private val tz = 420
  private val fs = FakeFileSystem()
  private val db: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
  private val pipeline = TextPipeline(JdkWordSegmenter)
  private val index = SearchIndexStore(db, pipeline, thaiSynonyms)
  private val memory = IndexedMemoryRepository(MemoryStore(fs, "/mem".toPath()), index)
  private val events = CaptureEventStore(db)
  private val places = PlaceStore(db)
  private val diary = DiaryComposer(events, places, memory, DiaryLedgerStore(db))
  private val assembler = ContextAssembler(memory, index, pipeline, thaiSynonyms)

  // Thursday 2026-10-29 16:00 Bangkok (the eval's run date)
  private val now = MemoryFormat.parseUtc("2026-10-29T09:00:00.000Z")!!

  @AfterTest fun close() = db.close()

  private suspend fun ask(q: String): List<String> {
    DemoSeed(events, places, memory, diary, EveningSummaryStore(fs, "/s".toPath()), ProfileStore(fs, "/p.txt".toPath()), DreamEngine(memory, pipeline))
      .seed(now, tz)
    return assembler.assemble(AssembleInput(q, BUDGET, now, tz)).items.map { it.text }
  }

  /** The first lines by time are what the chat cites, so the asked-for part of the day must lead. */
  @Test fun `this morning loads today and leads with the morning`() = runTest {
    val all = ask("วันนี้ตอนเช้าไปที่ไหนมาบ้าง")
    val recall = all.filter { it.startsWith("- [2026-") }
    assertTrue(recall.all { it.startsWith("- [2026-10-29") }, recall.toString())
    assertTrue(recall.take(3).any { "ร้านกาแฟหน้าออฟฟิศ" in it }, recall.toString())
    assertTrue(all.none { "[topics]" in it }, "'นี้' of วันนี้ must not match a core fact")
  }

  @Test fun `bare saturday is the most recent saturday`() = runTest {
    val lines = ask("วันเสาร์ไปวิ่งที่สวนไหนและวิ่งได้กี่รอบ").filter { it.startsWith("- [2026-") }
    assertTrue(lines.all { it.startsWith("- [2026-10-24") }, lines.toString())
    assertTrue(lines.any { "สวนลุมพินี" in it } && lines.any { "สามรอบ" in it })
    assertTrue(ask("วันอาทิตย์ไปที่บ้านแม่ที่ไหน").any { "2026-10-25" in it && "แกงเขียวหวาน" in it })
  }

  @Test fun `last week keeps every appointment within the chat budget`() = runTest {
    val text = ask("สัปดาห์ที่แล้วมีนัดอะไรบ้าง").joinToString(" | ")
    for (appt in listOf("กินข้าวกับเพื่อน", "ประชุมเช้าวันจันทร์", "หมอฟัน", "โทรคุยลูกค้า", "ประชุมทีมขาย")) assertTrue("“$appt”" in text, appt)
    // with no topic word, the one-off places still beat the daily commute lines
    val places = ask("สัปดาห์ที่แล้วไปไหนมาบ้าง").joinToString(" | ")
    for (p in listOf("คลินิกทันตกรรม", "ร้านหนังสือ สยาม", "บ้านแม่ นนทบุรี", "สวนลุมพินี", "ฟิตเนส")) assertTrue(p in places, p)
  }

  private companion object {
    const val BUDGET = 900 // ChatPresenter.DEFAULT_BUDGET_TOKENS
  }
}

/** JDK's dictionary word breaker, the JVM stand-in for the device's ICU segmenter. */
private object JdkWordSegmenter : WordSegmenter {
  override fun segment(text: String, languageCode: String): List<WordToken> {
    val it = BreakIterator.getWordInstance(Locale.forLanguageTag(languageCode)).apply { setText(text) }
    val out = mutableListOf<WordToken>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
      val w = text.substring(start, end)
      if (w.any { c -> c.isLetterOrDigit() }) out += WordToken(w, start, end)
      start = end
      end = it.next()
    }
    return out
  }
}
