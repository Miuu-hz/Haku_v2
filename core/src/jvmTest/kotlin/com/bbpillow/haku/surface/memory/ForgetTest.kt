package com.bbpillow.haku.surface.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceStore
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryLedgerStore
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.DREAM_LEDGER_DAY
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.memory.index.DictionarySegmenter
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.SearchQueryInput
import com.bbpillow.haku.network.ConnectionPurpose
import com.bbpillow.haku.network.ConnectionRecord
import com.bbpillow.haku.network.ConnectionStore
import com.bbpillow.haku.thinking.summary.EveningSummaryRecord
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import com.bbpillow.haku.thinking.summary.SummarySource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** P3.4 against the real stores (SQLite + files): forget a category, forget everything, nothing comes back. */
class ForgetTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private val fs = FakeFileSystem()
  private val db: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
  private var n = 0
  private val files = MemoryStore(fs, "/mem".toPath()) { "r${++n}" }
  private val index = SearchIndexStore(db, TextPipeline(DictionarySegmenter(setOf("coffee", "office"))))
  private val memory = IndexedMemoryRepository(files, index)
  private val events = CaptureEventStore(db)
  private val places = PlaceStore(db)
  private val ledger = DiaryLedgerStore(db)
  private val summaries = EveningSummaryStore(fs, "/summaries".toPath())
  private val profile = ProfileStore(fs, "/profile.txt".toPath())
  private val diary = DiaryComposer(events, places, memory, ledger)
  private val connections = ConnectionStore(db)
  private val at = 1_790_121_600_000L
  private val audit = com.bbpillow.haku.action.AuditStore(db)
  private val chats = com.bbpillow.haku.surface.chat.ChatHistoryStore(db)
  private val forgetAll = ForgetEverything(events, places, ledger, summaries, memory, profile, connections, audit, chats)
  private val presenter = TransparencyPresenter(memory, forgetAll, connections, tz) { at }

  @AfterTest fun close() = db.close()

  private fun hits(q: String) = index.search(SearchQueryInput(q, languageCode = "en")).size

  @Test fun forgetCategoryRemovesFileAndIndexRows() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "likes coffee", Provenance.USER_TYPED, at))
    memory.writeCoreFact(CoreFactInput(CoreCategories.TOPICS, "office politics", Provenance.USER_TYPED, at))
    assertEquals(1, hits("coffee"))

    presenter.forgetCategory(CoreCategories.YOU)

    assertFalse(fs.exists("/mem/core/you.md".toPath()))
    assertEquals(0, hits("coffee"))
    assertEquals(0, index.removeGroup(MemoryLevel.CORE, CoreCategories.YOU), "no rows left for the category")
    assertEquals(1, hits("office"), "other categories stay indexed")
  }

  private fun fillEverything() {
    events.appendAll(SyntheticWeek.events())
    events.append(CaptureEvent("cal-1", at, CaptureSource.CalendarEntry("dentist")))
    places.addNew(listOf(PlaceRecord("p1", 13.75, 100.5)))
    connections.add(ConnectionRecord.of(at, "https://huggingface.co/m.litertlm", ConnectionPurpose.MODEL_DOWNLOAD, 10, null, "สำเร็จ"))
    audit.append(com.bbpillow.haku.action.AuditRecord("p1", "calendar.add", com.bbpillow.haku.action.ActionOrigin.CHAT_RULE, com.bbpillow.haku.action.PermissionLevel.CONFIRM, com.bbpillow.haku.action.ActionStatus.DONE, "dentist", at))
    diary.compose(DiaryComposeInput("2026-09-08", tz))
    summaries.save(EveningSummaryRecord("2026-09-08", "coffee day", SummarySource.MODEL))
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "likes coffee", Provenance.USER_TYPED, at))
    memory.addStanding(NoteInput("always coffee", Provenance.USER_TYPED, at))
    memory.addIntention(NoteInput("buy coffee", Provenance.USER_TYPED, at))
    memory.appendDaily(DailyEventInput("10:00 coffee", Provenance.USER_TYPED, at))
    memory.writeDreamReport("2026-09-23", "coffee dream")
    memory.writeDreamReport(DREAM_LEDGER_DAY, "P	key	you	f1")
  }

  private fun assertNothingLeft() {
    assertEquals(0, events.count())
    assertTrue(places.list().isEmpty())
    assertTrue(connections.since(0).isEmpty())
    assertTrue(audit.recent(10).isEmpty())
    assertTrue(presenter.load().connections.isEmpty())
    assertTrue(memory.listDays().isEmpty())
    assertTrue(memory.listCategories().all { it.factCount == 0 })
    assertTrue(memory.readStanding().isEmpty() && memory.readIntentions().isEmpty())
    assertTrue(memory.listDreamDays().isEmpty())
    assertNull(memory.readDreamReport(DREAM_LEDGER_DAY), "dream ledger must go too")
    assertEquals(0, index.count())
    assertNull(summaries.find("2026-09-08"))
  }

  @Test fun forgetEverythingLeavesNoDataAndNothingRegenerates() {
    fillEverything()
    assertTrue(events.count() > 0 && memory.listDays().isNotEmpty())

    presenter.forgetEverything()
    assertNothingLeft()

    // the nightly job and a calendar re-read run again: nothing comes back
    val replay = SyntheticWeek.events() + CaptureEvent("cal-1", at, CaptureSource.CalendarEntry("dentist"))
    assertEquals(0, events.appendAll(replay), "replayed events were tombstoned")
    events.replaceCalendar(0, Long.MAX_VALUE, replay.filter { it.source is CaptureSource.CalendarEntry })
    assertTrue(events.query(CaptureQueryInput(0, Long.MAX_VALUE)).isEmpty())
    for (day in listOf("2026-09-07", "2026-09-08", "2026-09-09")) {
      assertTrue(diary.compose(DiaryComposeInput(day, tz)).written.isEmpty())
    }
    assertNothingLeft()
  }

  @Test fun forgetEverythingErasesSavedChatsEvenPinnedOnes() {
    val answer = com.bbpillow.haku.surface.chat.ChatAnswerOutput("q", "a", emptyList(), com.bbpillow.haku.surface.chat.ChatAnswerSource.GENERAL)
    chats.append(1L, at, answer)
    chats.setPinned(1L, true)
    forgetAll.run()
    assertEquals(emptyList(), chats.sessions())
    assertEquals(emptyList(), chats.answers(1L))
  }

  @Test fun forgetEverythingOnAnEmptyAppIsHarmless() {
    presenter.forgetEverything()
    assertNothingLeft()
  }

  @Test fun forgetEverythingErasesNicknameButKeepsSettings() {
    val kept = ProfileRecord(DiaryStyle(particle = EndingParticle.KA, nickname = "น้องแบงค์"), ActiveModel.Catalog(ModelChoice.E4B), lockEnabled = true, onboarded = true)
    profile.save(kept)
    presenter.forgetEverything()
    assertEquals(kept.copy(style = kept.style.copy(nickname = "")), profile.load())
    assertEquals("", profile.load().style.nickname)
  }

  @Test fun dreamLedgerIsNotListedOrExported() {
    memory.writeDreamReport(DREAM_LEDGER_DAY, "P	secret-key	you	f1")
    memory.writeDreamReport("2026-09-23", "real report")
    assertEquals(listOf("2026-09-23"), memory.listDreamDays())
    assertFalse(presenter.exportMarkdown().contains("secret-key"))
    assertEquals(listOf("real report"), presenter.load().dreamReports.map { it.text })
  }
}
