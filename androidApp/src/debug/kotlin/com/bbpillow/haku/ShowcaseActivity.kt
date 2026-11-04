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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceGuessOutput
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ScreenBackdrop
import com.bbpillow.haku.surface.home.BriefPeriod
import com.bbpillow.haku.surface.home.HomeBriefScreen
import com.bbpillow.haku.surface.home.HomeBriefUiState
import com.bbpillow.haku.surface.home.HomeStats
import com.bbpillow.haku.surface.home.LearningCard
import com.bbpillow.haku.surface.memory.MemoryCategoryItem
import com.bbpillow.haku.surface.memory.MemoryFact
import com.bbpillow.haku.surface.memory.TransparencyScreen
import com.bbpillow.haku.surface.memory.TransparencyUiState
import com.bbpillow.haku.surface.onboarding.OnboardingActions
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.OnboardingScreen
import com.bbpillow.haku.surface.onboarding.OnboardingUiState
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileRepository
import com.bbpillow.haku.surface.showcase.ComponentShowcase
import com.bbpillow.haku.surface.theme.HakuTheme
import com.bbpillow.haku.surface.timeline.TimelineDate
import com.bbpillow.haku.surface.timeline.TimelineLine
import com.bbpillow.haku.surface.timeline.TimelineScreen
import com.bbpillow.haku.surface.timeline.TimelineUiState

/**
 * Debug screen for eyeballing the UI kit and the P3.1/P3.2/P3.4/P3.6 screens against haku-mockup.html, with fake data.
 * Tabs at the top: kit, timeline, memory, home, onboarding. Open straight on a tab with `--ei tab N` (1 timeline,
 * 2 memory, 3 home, 4 onboarding).
 */
// ponytail: fake in-memory state only; the real screens over real data are in MainActivity (MainShell / OnboardingHost).
class ShowcaseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val start = intent.getIntExtra("tab", 0)
    setContent { HakuTheme { ShowcaseTabs(start) } }
  }
}

private val Tabs = listOf("ชุดคอมโพเนนต์", "ไทม์ไลน์", "ความจำ", "หน้าแรก", "เริ่มใช้งาน")

@Composable
private fun ShowcaseTabs(start: Int) {
  var tab by remember { mutableIntStateOf(start.coerceIn(0, Tabs.lastIndex)) }
  var timeline by remember { mutableStateOf(fakeTimeline(FAKE_TODAY)) }
  var memory by remember { mutableStateOf(fakeMemory()) }
  var home by remember { mutableStateOf(fakeHome(BriefPeriod.EVENING)) }
  ScreenBackdrop(Modifier.fillMaxSize()) {
    // safeDrawingPadding consumes the insets, so the screens below do not pad twice
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
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
          3 -> Column {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf("เช้า" to BriefPeriod.MORNING, "เย็น" to BriefPeriod.EVENING, "วันแรก" to null).forEach { (label, period) ->
                CapsuleButton(label, { home = fakeHome(period) }, outline = true)
              }
            }
            HomeBriefScreen(
              home,
              isCapturing = !home.isDayOne,
              onOpenDay = { tab = 1 },
              onReadLearning = {},
              onDismissLearning = { home = home.copy(learning = null) },
            )
          }
          4 -> FakeOnboarding()
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

/** Fake brief per variant; null = day one. Numbers are fake here only, the presenter never invents them. */
private fun fakeHome(period: BriefPeriod?): HomeBriefUiState {
  if (period == null) {
    return HomeBriefUiState(
      BriefPeriod.EVENING, "สวัสดีตอนเย็น มิ้นท์", "Haku กำลังเรียนรู้วันของคุณ", "เรื่องของวันนี้", FAKE_TODAY,
      ThaiDateFormat.dayHeader(FAKE_TODAY), emptyList(), 0, null, isDayOne = true,
    )
  }
  val morning = period == BriefPeriod.MORNING
  val day = if (morning) "2026-09-23" else FAKE_TODAY
  return HomeBriefUiState(
    period = period,
    greeting = if (morning) "สวัสดีตอนเช้า มิ้นท์" else "สวัสดีตอนเย็น มิ้นท์",
    headline = if (morning) "เมื่อวานเป็นยังไงบ้าง" else "วันนี้เป็นยังไงบ้าง",
    storyTitle = if (morning) "เมื่อวาน" else "เรื่องของวันนี้",
    storyDay = day,
    storyHeader = ThaiDateFormat.dayHeader(day),
    storyLines = listOf("08:12 ออกจากบ้าน", "08:45 ถึงที่ทำงาน ใช้เวลาเดินทางราว 30 นาที", "12:30 ร้านข้าวมันไก่ อยู่ราว 40 นาที"),
    storyLineCount = 5,
    stats = if (morning) HomeStats(3) else HomeStats(4, 0.7f, 545, steps = 7420),
    learning = LearningCard(
      "ทำไมการเดินหลังอาหารช่วยเรื่องน้ำตาล",
      "เดินเบา ๆ 10–15 นาทีหลังมื้อช่วยให้กล้ามเนื้อดึงน้ำตาลไปใช้ ระดับน้ำตาลจึงขึ้นช้าลง",
      "สุขภาพ",
      "Wikipedia",
    ).takeIf { !morning },
  )
}

private object MemoryProfile : ProfileRepository {
  private var saved = ProfileRecord()
  override fun load() = saved
  override fun save(profile: ProfileRecord) { saved = profile }
}

private object NoPlaces : PlaceRepository {
  override fun list() = emptyList<PlaceRecord>()
  override fun addNew(places: List<PlaceRecord>) = 0
  override fun confirm(id: String, label: String, category: PlaceCategory) = true
  override fun delete(id: String) = false
  override fun deleteAll() = Unit
}

/** The real presenter over in-memory fakes; OS side effects are simulated (grant all, instant 64% download, lock on). */
@Composable
private fun FakeOnboarding() {
  val presenter = remember { OnboardingPresenter(MemoryProfile, NoPlaces) }
  fun fresh(): OnboardingUiState = presenter.start(ramGb = 12, placeGuess = PlaceGuessOutput("p1", PlaceCategory.HOME, 5))
  var s by remember { mutableStateOf(fresh()) }
  val actions = OnboardingActions(
    onNext = { s = presenter.next(s) },
    onBack = { s = presenter.back(s) },
    onSkip = { s = presenter.skip(s) },
    onTogglePermission = { s = presenter.togglePermission(s, it) },
    onRequestPermissions = { s = presenter.permissionsResult(s, presenter.permissionRequests(s).toSet()) },
    onParticle = { s = presenter.setParticle(s, it) },
    onAddress = { s = presenter.setAddress(s, it) },
    onNickname = { s = presenter.setNickname(s, it) },
    onChooseModel = { s = presenter.chooseModel(s, it) },
    onStartDownload = { s = presenter.downloadUpdate(s, 0.64f) },
    onPauseDownload = { s = presenter.downloadUpdate(s, s.downloadProgress ?: 0f, paused = it) },
    onEnableLock = { s = presenter.lockResult(s, true) },
    onConfirmPlace = { s = presenter.confirmPlace(s, it) },
    onRejectPlace = { s = presenter.rejectPlace(s) },
  )
  Column {
    OnboardingScreen(s, actions, Modifier.weight(1f))
    if (s.complete) CapsuleButton("เริ่มใหม่", { s = fresh() }, Modifier.padding(20.dp))
  }
}

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
