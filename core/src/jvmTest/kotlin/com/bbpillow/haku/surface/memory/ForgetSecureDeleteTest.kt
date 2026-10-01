package com.bbpillow.haku.surface.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceStore
import com.bbpillow.haku.capture.diary.DiaryLedgerStore
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.index.DictionarySegmenter
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Forgotten text must not stay recoverable in SQLite free pages. */
class ForgetSecureDeleteTest {
  @Test fun forgetEverythingLeavesNoDeletedTextInTheDbFile() {
    val file = File.createTempFile("haku-forget", ".db").also { it.deleteOnExit() }
    val db = BundledSQLiteDriver().open(file.path)
    val fs = FakeFileSystem()
    var n = 0
    val index = SearchIndexStore(db, TextPipeline(DictionarySegmenter(setOf("zebra"))))
    val memory = IndexedMemoryRepository(MemoryStore(fs, "/mem".toPath()) { "r${++n}" }, index)
    val events = CaptureEventStore(db)
    val places = PlaceStore(db)
    val ledger = DiaryLedgerStore(db)
    val secret = "zebrasecretphrase"
    events.append(CaptureEvent("cal-1", 1_790_121_600_000L, CaptureSource.CalendarEntry(secret)))
    repeat(200) { memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "$secret $it", Provenance.USER_TYPED, 1L)) }
    assertTrue(String(file.readBytes(), Charsets.ISO_8859_1).contains(secret), "precondition: text is in the file")

    ForgetEverything(events, places, ledger, EveningSummaryStore(fs, "/s".toPath()), memory, ProfileStore(fs, "/p.txt".toPath())).run()
    db.close()

    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(secret))
  }
}
