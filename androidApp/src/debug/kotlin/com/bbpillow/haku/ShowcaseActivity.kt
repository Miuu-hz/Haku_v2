package com.bbpillow.haku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ScreenBackdrop
import com.bbpillow.haku.surface.memory.MemoryCategoryItem
import com.bbpillow.haku.surface.memory.MemoryFact
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
import com.bbpillow.haku.surface.showcase.ComponentShowcase
import com.bbpillow.haku.surface.theme.HakuTheme
import com.bbpillow.haku.surface.timeline.TimelineDate
import com.bbpillow.haku.surface.timeline.TimelineLine
import com.bbpillow.haku.surface.timeline.TimelineScreen
import com.bbpillow.haku.surface.timeline.TimelineUiState

/**
 * Debug screen for eyeballing the UI kit and the P3.2/P3.4 screens against haku-mockup.html, with fake data.
 * Tabs at the top: kit, timeline, memory. Open straight on a tab with `--ei tab 1` (timeline) or `--ei tab 2` (memory).
 */
// ponytail: fake in-memory state only; MainActivity is wired to TimelinePresenter/TransparencyPresenter when navigation lands.
class ShowcaseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val start = intent.getIntExtra("tab", 0)
    setContent { HakuTheme { ShowcaseTabs(start) } }
  }
}

private val Tabs = listOf("ชุดคอมโพเนนต์", "ไทม์ไลน์", "ความจำ")

@Composable
private fun ShowcaseTabs(start: Int) {
  var tab by remember { mutableIntStateOf(start.coerceIn(0, Tabs.lastIndex)) }
  var timeline by remember { mutableStateOf(fakeTimeline(FAKE_TODAY)) }
  var memory by remember { mutableStateOf(fakeMemory()) }
  ScreenBackdrop(Modifier.fillMaxSize()) {
    // safeDrawingPadding consumes the insets, so the screens below do not pad twice
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tabs.forEachIndexed { i, label -> CapsuleButton(label, { tab = i }, outline = i != tab) }
      }
      Box(Modifier.weight(1f)) {
        when (tab) {
          0 -> ComponentShowcase()
          1 -> TimelineScreen(
            timeline,
            onSelectDay = { timeline = fakeTimeline(it) },
            onEdit = { line, text ->
              timeline = timeline.copy(lines = timeline.lines.map { if (it == line) it.copy(text = text.trim(), provenance = Provenance.USER_TYPED) else it })
            },
            onDelete = { line -> timeline = timeline.copy(lines = timeline.lines - line) },
          )
          else -> TransparencyScreen(
            memory,
            onForget = { f ->
              memory = memory.copy(categories = memory.categories.map { c -> (c.facts - f).let { c.copy(facts = it, count = it.size) } })
            },
          )
        }
      }
    }
  }
}

private const val FAKE_TODAY = "2026-09-24"

private fun fakeTimeline(day: String): TimelineUiState {
  val dates = (14 downTo 0).map { back ->
    val d = "2026-09-${(24 - back).toString().padStart(2, '0')}"
    TimelineDate(d, ThaiDateFormat.shortWeekday(d), 24 - back)
  }
  fun line(id: String, time: String, text: String, p: Provenance = Provenance.INFERRED_FROM_USER_DATA) =
    TimelineLine(day, id, 0L, time, text, p)
  val lines = if (day != FAKE_TODAY) emptyList() else listOf(
    line("a", "08:12", "ออกจากบ้าน"),
    line("b", "08:45", "ถึงที่ทำงาน ใช้เวลาเดินทางราว 30 นาที อยู่ราว 4 ชั่วโมง"),
    line("c", "12:30", "ร้านข้าวมันไก่ อยู่ราว 40 นาที", Provenance.USER_TYPED),
    line("d", "14:00", "นัด ประชุมทีมขาย ถึง 15:00"),
    line("e", "18:10", "ถึงสถานที่หนึ่ง อยู่ราว 1 ชั่วโมง"),
  )
  return TimelineUiState(day, FAKE_TODAY, ThaiDateFormat.dayHeader(day), dates, lines)
}

private fun fakeMemory(): TransparencyUiState {
  fun fact(cat: String, id: String, text: String, date: String, p: Provenance) = MemoryFact(cat, id, text, date, p)
  fun cat(slug: String, title: String, desc: String, vararg facts: MemoryFact) = MemoryCategoryItem(slug, title, desc, facts.size, facts.toList())
  return TransparencyUiState(
    listOf(
      cat(
        "you", "ตัวคุณ", "ความชอบและโปรไฟล์",
        fact("you", "1", "ชอบกาแฟไม่หวาน", "23 ก.ย.", Provenance.USER_TYPED),
        fact("you", "2", "เข้างานราว 08:45", "20 ก.ย.", Provenance.INFERRED_FROM_USER_DATA),
      ),
      cat(
        "topics", "เรื่องที่สนใจ", "เรื่องที่คุณติดตามหรือใส่ใจ",
        fact("topics", "3", "สุขภาพ · ออกกำลังกาย", "18 ก.ย.", Provenance.INFERRED_FROM_USER_DATA),
        fact("topics", "4", "บทความเรื่องน้ำตาล", "17 ก.ย.", Provenance.EXTERNAL),
      ),
      cat("areas", "ด้านของชีวิต", "งาน สุขภาพ ครอบครัว"),
      cat("projects", "โครงการ", "สิ่งที่คุณกำลังทำอยู่", fact("projects", "5", "ย้ายคอนโดเดือนหน้า", "12 ก.ย.", Provenance.USER_TYPED)),
    ),
    egressToday = 0,
  )
}
