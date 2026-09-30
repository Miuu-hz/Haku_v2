package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.SyntheticWeek
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.WordSegmenter
import com.bbpillow.haku.language.WordToken
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Dream tidy against a real DiaryComposer: the diary ledger must never end up pointing at a record Dream deleted. */
class DreamDiaryTidyTest {
  private val tz = SyntheticWeek.TZ_OFFSET_MIN
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "r${++n}" }
  private val ledger = FakeLedger()
  private val diary = DiaryComposer(FakeEvents(SyntheticWeek.events()), FakePlaces(emptyList()), memory, ledger)
  private val pipeline = TextPipeline(object : WordSegmenter {
    override fun segment(text: String, languageCode: String) = listOf(WordToken(text, 0, text.length))
  })
  private val tuesday = "2026-09-08"

  @Test fun `tidy never deletes a diary line, even when the user typed the same words`() = runTest {
    diary.compose(DiaryComposeInput(tuesday, tz))
    val lines = diary.readDay(tuesday, tz)
    val twin = lines.first()
    memory.appendDaily(DailyEventInput(twin.text, Provenance.USER_TYPED, twin.atEpochMs))

    val out = DreamEngine(memory, pipeline).dream(DreamInput("2026-09-09"))

    assertTrue(out.tidied.none { it.id == twin.id }, "diary line was tidied")
    assertEquals(lines.map { it.id }, diary.readDay(tuesday, tz).filter { it.provenance == Provenance.INFERRED_FROM_USER_DATA }.map { it.id })
    for (row in ledger.rows.values) assertNotNull(memory.readDay(row.memoryDay).firstOrNull { it.id == row.memoryRecordId }, "ledger points at a missing record")
    assertTrue(diary.compose(DiaryComposeInput(tuesday, tz)).written.isEmpty(), "compose must not re-write or double lines")
  }

  @Test fun `two identical inferred records of one day are both kept`() = runTest {
    val at = 1_790_121_600_000L
    val a = memory.appendDaily(DailyEventInput("ถึงบ้าน", Provenance.INFERRED_FROM_USER_DATA, at))
    val b = memory.appendDaily(DailyEventInput("ถึงบ้าน", Provenance.INFERRED_FROM_USER_DATA, at))
    val out = DreamEngine(memory, pipeline).dream(DreamInput("2026-09-24"))
    assertTrue(out.tidied.isEmpty())
    assertEquals(listOf(a.id, b.id), memory.readDay(MemoryFormat.dayOf(at)).map { it.id })
  }
}
