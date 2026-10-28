package com.bbpillow.haku.surface.note

import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.surface.home.BriefPeriod
import com.bbpillow.haku.surface.home.CaptureStatus
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.home.HomeWidget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class QuickNoteTest {
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath())
  private val note = QuickNote(memory, tzOffsetMinutes = 420)
  private val at = requireNotNull(MemoryFormat.parseUtc("2026-10-29T02:05:00.000Z")) // 09:05 in Bangkok

  @Test
  fun savesOneLineAsTodaysUserNoteWithLocalTime() {
    val saved = note.save(QuickNoteInput("  ซื้อนม\nก่อนกลับบ้าน  ", at))!!
    assertEquals("09:05 ซื้อนม ก่อนกลับบ้าน", saved.text)
    assertEquals(Provenance.USER_TYPED, saved.provenance)
    assertEquals(listOf(saved), memory.readDay("2026-10-29"))
  }

  @Test
  fun blankSavesNothing() {
    assertNull(note.save(QuickNoteInput(" \n\t ", at)))
    assertEquals(emptyList(), memory.listDays())
  }

  @Test
  fun capsLengthWithoutSplittingAnEmoji() {
    assertEquals(QuickNote.MAX_CHARS, QuickNote.clean("ก".repeat(900))!!.length)
    assertEquals("ก".repeat(QuickNote.MAX_CHARS - 1), QuickNote.clean("ก".repeat(QuickNote.MAX_CHARS - 1) + "😀"))
  }

  private val brief = HomeBriefUiState(
    BriefPeriod.MIDDAY, "สวัสดีตอนบ่าย", "บ่ายนี้มีอะไรบ้าง", "วันนี้จนถึงตอนนี้", "2026-10-29", "",
    storyLines = listOf("08:10 ออกจากบ้าน", "09:00 ถึงที่ทำงาน", "12:00 ร้านข้าว"), storyLineCount = 3, stats = null,
  )

  @Test
  fun widgetShowsNoDiaryTextOrStatusWhenTheAppLockIsOn() {
    assertEquals(HomeWidget.content(null, false, CaptureStatus.OFF), HomeWidget.content(brief, true, CaptureStatus.MOVING))
    val locked = HomeWidget.content(brief, lockEnabled = true, CaptureStatus.MOVING)
    assertEquals("Haku", locked.title)
    assertNull(locked.body)
    assertNull(locked.status)
  }

  @Test
  fun widgetShowsSummaryElseTwoLinesElseHeadline() {
    val open = HomeWidget.content(brief, false, CaptureStatus.MOVING)
    assertEquals("วันนี้จนถึงตอนนี้", open.title)
    assertEquals("08:10 ออกจากบ้าน\n09:00 ถึงที่ทำงาน", open.body)
    assertEquals(HomeWidget.statusText(CaptureStatus.MOVING), open.status)
    assertEquals("สรุป", HomeWidget.content(brief.copy(storySummary = "สรุป"), false, CaptureStatus.OFF).body)
    assertEquals("บ่ายนี้มีอะไรบ้าง", HomeWidget.content(brief.copy(storyLines = emptyList()), false, CaptureStatus.OFF).body)
  }
}
