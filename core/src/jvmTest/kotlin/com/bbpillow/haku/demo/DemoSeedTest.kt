package com.bbpillow.haku.demo

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.PlaceStore
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryLedgerStore
import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.capture.stats.StatsSummarizer
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.index.DictionarySegmenter
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.surface.home.BriefPeriod
import com.bbpillow.haku.surface.home.HomeBriefPresenter
import com.bbpillow.haku.surface.memory.ForgetEverything
import com.bbpillow.haku.surface.memory.TransparencyPresenter
import com.bbpillow.haku.surface.onboarding.AppStart
import com.bbpillow.haku.surface.onboarding.ProfileStore
import com.bbpillow.haku.surface.onboarding.StartScreen
import com.bbpillow.haku.surface.timeline.TimelinePresenter
import com.bbpillow.haku.thinking.summary.EveningSummaryStore
import com.bbpillow.haku.thinking.summary.SummarySource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** The demo seeder against the real stores (SQLite + memory files + index): screens fill, re-seed is a no-op, forget empties it. */
class DemoSeedTest {
  private val tz = 420
  private val fs = FakeFileSystem()
  private val db: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
  private val pipeline = TextPipeline(DictionarySegmenter(setOf("ที่ทำงาน", "ร้านกาแฟ", "ฟิตเนส", "กาแฟ")))
  private val index = SearchIndexStore(db, pipeline)
  private val memory = IndexedMemoryRepository(MemoryStore(fs, "/mem".toPath()), index)
  private val events = CaptureEventStore(db)
  private val places = PlaceStore(db)
  private val ledger = DiaryLedgerStore(db)
  private val summaries = EveningSummaryStore(fs, "/summaries".toPath())
  private val profile = ProfileStore(fs, "/profile.txt".toPath())
  private val diary = DiaryComposer(events, places, memory, ledger)
  private val connections = com.bbpillow.haku.network.ConnectionStore(db)
  private val forget = ForgetEverything(events, places, ledger, summaries, memory, profile, connections)
  private val free = object : Entitlements { override val plan = Plan.FREE }
  private val home = HomeBriefPresenter(diary, events, places, tz, summaries, StatsSummarizer(free))
  private val timeline = TimelinePresenter(diary, memory, summaries, tz)
  private val transparency = TransparencyPresenter(memory, forget, connections, tz) { now }

  // Thursday 2026-10-01 20:00 Bangkok, the mockup's evening
  private val now = MemoryFormat.parseUtc("2026-10-01T13:00:00.000Z")!!
  private val today = "2026-10-01"
  private val lastWeek = "2026-09-24"

  private fun seeder() = DemoSeed(events, places, memory, diary, summaries, profile, DreamEngine(memory, pipeline))

  @AfterTest fun close() = db.close()

  @Test fun seedFillsEveryScreen() = runTest {
    val out = seeder().seed(now, tz)
    assertFalse(out.alreadySeeded)
    assertEquals(DemoSeed.DAYS - 1, out.summaries, "every past day gets a checked summary")
    assertTrue(out.events > 100 && out.diaryLines > 80 && out.promotions > 0, out.toString())

    val p = profile.load()
    assertEquals(StartScreen.MAIN, AppStart.screen(p, unlocked = false))
    assertEquals("มิ้นท์", p.style.nickname)

    val brief = home.load(now, p.style)
    assertEquals(BriefPeriod.EVENING, brief.period)
    assertEquals("สวัสดีตอนเย็น มิ้นท์", brief.greeting)
    assertTrue(brief.storyLines.first().startsWith("08:12 ออกจากบ้าน"), brief.storyLines.toString())
    assertTrue(brief.stats!!.placeCount >= 4)
    assertTrue(brief.weekly!!.lines.isNotEmpty(), "weekly card has numbers")

    val morning = home.load(MemoryFormat.parseUtc("2026-09-25T01:00:00.000Z")!!, p.style) // Fri 08:00 looks at Thursday
    assertEquals(lastWeek, morning.storyDay)
    assertNotNull(morning.storySummary)
    assertEquals(SummarySource.DEMO, summaries.find(lastWeek)!!.source)

    val todayLines = timeline.load(today, today).lines
    assertTrue(todayLines.any { it.text.startsWith("ถึงร้านข้าวมันไก่") } && todayLines.any { it.text.contains("“ประชุมทีมขาย”") })
    assertTrue(timeline.load(lastWeek, today).lines.size >= 8)

    val t = transparency.load()
    assertTrue(t.categories.sumOf { it.count } > 6, "user facts plus Dream promotions")
    assertTrue(t.categories.flatMap { it.facts }.any { it.provenance == Provenance.INFERRED_FROM_USER_DATA })
    assertTrue(t.dreamReports.isNotEmpty())
    assertTrue(index.count() > 100, "search index filled")

    println("TODAY:\n" + diary.readDay(today, tz).joinToString("\n") { it.text })
    println("SUMMARY $lastWeek: ${summaries.find(lastWeek)!!.text}")
    println("FACTS: " + t.categories.joinToString { c -> c.slug + "=" + c.facts.map { it.text } })
    println("OUT: $out")
  }

  @Test fun seedingTwiceIsANoOp() = runTest {
    seeder().seed(now, tz)
    val before = listOf(events.count(), index.count(), places.list().size, memory.listDays().sumOf { memory.readDay(it).size })
    assertTrue(seeder().seed(now + 60_000, tz).alreadySeeded)
    assertEquals(before, listOf(events.count(), index.count(), places.list().size, memory.listDays().sumOf { memory.readDay(it).size }))
  }

  @Test fun forgetEverythingLeavesNothingAndReseedWorks() = runTest {
    seeder().seed(now, tz)
    transparency.forgetEverything()

    assertEquals(0, events.count())
    assertTrue(places.list().isEmpty())
    assertTrue(memory.listDays().isEmpty() && memory.listDreamDays().isEmpty())
    assertTrue(memory.listCategories().all { it.factCount == 0 })
    assertTrue(memory.readStanding().isEmpty() && memory.readIntentions().isEmpty())
    assertEquals(0, index.count())
    assertTrue((0 until DemoSeed.DAYS).none { summaries.find(MemoryFormat.dayOf(now - it * 86_400_000L)) != null })
    assertEquals("", profile.load().style.nickname)

    // tombstones hold the old ids only: a later demo run still loads
    val again = seeder().seed(now + 5_000, tz)
    assertFalse(again.alreadySeeded)
    assertTrue(again.events > 100)
  }
}
