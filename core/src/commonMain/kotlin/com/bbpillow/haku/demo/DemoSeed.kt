package com.bbpillow.haku.demo

import com.bbpillow.haku.capture.ActivityKind
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventRepository
import com.bbpillow.haku.capture.CaptureIds
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryComposeInput
import com.bbpillow.haku.capture.diary.DiaryComposer
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.dream.DreamEngine
import com.bbpillow.haku.memory.dream.DreamInput
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileRepository
import com.bbpillow.haku.thinking.summary.EveningSummaryRecord
import com.bbpillow.haku.thinking.summary.EveningSummaryRepository
import com.bbpillow.haku.thinking.summary.SummaryCheckInput
import com.bbpillow.haku.thinking.summary.SummaryChecker
import com.bbpillow.haku.thinking.summary.SummarySource

/** What one seeding run wrote. [alreadySeeded] = demo data was found, nothing was touched. */
data class DemoSeedOutput(
  val alreadySeeded: Boolean,
  val events: Int = 0,
  val places: Int = 0,
  val diaryLines: Int = 0,
  val userNotes: Int = 0,
  val coreFacts: Int = 0,
  val intentions: Int = 0,
  val summaries: Int = 0,
  val dreamNights: Int = 0,
  val promotions: Int = 0,
)

/** The persona's places (Bangkok: home in Ari, office in Silom). Seeded already confirmed, as if the user named them. */
enum class DemoPlace(val label: String, val category: PlaceCategory, val lat: Double, val lon: Double) {
  HOME("บ้าน", PlaceCategory.HOME, 13.7796, 100.5446),
  OFFICE("ที่ทำงาน", PlaceCategory.WORK, 13.7262, 100.5310),
  CAFE("ร้านกาแฟหน้าออฟฟิศ", PlaceCategory.OTHER, 13.7275, 100.5335),
  LUNCH("ร้านข้าวมันไก่", PlaceCategory.OTHER, 13.7248, 100.5290),
  GYM("ฟิตเนส", PlaceCategory.OTHER, 13.7290, 100.5365),
  PARK("สวนลุมพินี", PlaceCategory.OTHER, 13.7314, 100.5414),
  MALL("เซ็นทรัลเวิลด์", PlaceCategory.OTHER, 13.7466, 100.5393),
  DENTIST("คลินิกทันตกรรม", PlaceCategory.OTHER, 13.7720, 100.5420),
  BOOKSTORE("ร้านหนังสือ สยาม", PlaceCategory.OTHER, 13.7456, 100.5330),
  MOM("บ้านแม่ นนทบุรี", PlaceCategory.OTHER, 13.8621, 100.5144);

  val id: String get() = "demo-place-${name.lowercase()}"
}

/**
 * DEBUG ONLY (reachable from androidApp/src/debug). Two weeks of "มิ้นท์" (haku-mockup.html persona) written through the
 * real stores: capture events, confirmed places, the rule-based diary (DiaryComposer), notes, core facts, hand-written
 * evening summaries ([SummarySource.DEMO], each passed through [SummaryChecker] against that day's diary) and Dream.
 * Deterministic content; event ids carry a run stamp so a seed after "ลืมทุกอย่าง" is not blocked by the tombstones.
 * Idempotent: any `demo-` event in the window means already seeded.
 */
class DemoSeed(
  private val events: CaptureEventRepository,
  private val places: PlaceRepository,
  private val memory: MemoryRepository,
  private val diary: DiaryComposer,
  private val summaries: EveningSummaryRepository,
  private val profile: ProfileRepository,
  private val dream: DreamEngine,
) {
  suspend fun seed(nowUtcMs: Long, tzOffsetMinutes: Int): DemoSeedOutput {
    val tz = tzOffsetMinutes * MIN_MS
    val today = MemoryFormat.dayOf(nowUtcMs + tz)
    val days = (DAYS - 1 downTo 0).map { plusDays(today, -it) }
    val windowStart = dayStart(days.first(), tz) - DAY_MS
    if (events.query(CaptureQueryInput(windowStart, nowUtcMs + 1)).any { it.id.startsWith(PREFIX) }) return DemoSeedOutput(true)

    val style = DiaryStyle(EndingParticle.KA, AddressForm.NICKNAME, NICKNAME)
    profile.save(ProfileRecord(style, ActiveModel.DEFAULT, lockEnabled = false, onboarded = true))
    places.addNew(DemoPlace.entries.map { PlaceRecord(it.id, it.lat, it.lon) })
    DemoPlace.entries.forEach { places.confirm(it.id, it.label, it.category) }

    val plans = days.mapIndexed { i, d -> d to DemoDays.plan(weekday(d), occurrence = i / 7, jitter = if (i == DAYS - 1) 0 else (i * 7 + 3) % 9 - 4) }
    val stamp = "$PREFIX${(nowUtcMs / 1000).toString(36)}-"
    val written = events.appendAll(captureEvents(plans, tz, nowUtcMs, stamp))

    val start = dayStart(days.first(), tz)
    val longTerm = writeLongTerm(start)
    var notes = 0
    for ((d, p) in plans) for (n in p.notes) {
      val at = dayStart(d, tz) + n.minute * MIN_MS
      if (at <= nowUtcMs) { memory.appendDaily(DailyEventInput("${hhmm(n.minute)} ${n.text}", Provenance.USER_TYPED, at)); notes++ }
    }

    val lines = days.sumOf { diary.compose(DiaryComposeInput(it, tzOffsetMinutes, style)).written.size }
    var saved = 0
    for ((d, p) in plans.dropLast(1)) {
      val dayFacts = diary.readDay(d, tzOffsetMinutes).map { it.text }
      // never store a sentence the day's own facts do not back (same gate as a model summary)
      if (summaries.find(d) == null && SummaryChecker.check(SummaryCheckInput(p.summary, dayFacts, style)).ok) {
        summaries.save(EveningSummaryRecord(d, p.summary, SummarySource.DEMO)); saved++
      }
    }
    val promoted = days.dropLast(1).sumOf { dream.dream(DreamInput(it)).promoted.size }
    return DemoSeedOutput(false, written, DemoPlace.entries.size, lines, notes, longTerm.first, longTerm.second, saved, DAYS - 1, promoted)
  }

  /** Stays become dwell start/end pairs (end only once it has happened), trips become activity transitions. */
  private fun captureEvents(plans: List<Pair<String, DemoDayPlan>>, tz: Long, now: Long, stamp: String): List<CaptureEvent> {
    data class Abs(val place: DemoPlace, val start: Long, val end: Long)
    val stays = mutableListOf<Abs>()
    for ((d, p) in plans) for (s in p.stays) {
      val a = Abs(s.place, dayStart(d, tz) + s.start * MIN_MS, dayStart(d, tz) + s.end * MIN_MS)
      val last = stays.lastOrNull()
      if (last != null && last.place == a.place && last.end == a.start) stays[stays.lastIndex] = last.copy(end = a.end) else stays += a
    }
    stays[0] = stays[0].copy(start = stays[0].start - FIRST_EVENING_MIN * MIN_MS) // the evening before day one, not midnight
    val out = mutableListOf<CaptureEvent>()
    stays.filter { it.start <= now }.forEachIndexed { i, s ->
      // ~20 m of deterministic GPS wobble per stay
      val wobble = ((i * 13) % 7 - 3) * 0.00007
      val at = EventPlace(s.place.lat + wobble, s.place.lon - wobble)
      out += CaptureEvent(stamp + CaptureIds.dwellStart(s.start), s.start, CaptureSource.DwellStart, at)
      if (s.end <= now) out += CaptureEvent(stamp + CaptureIds.dwellEnd(s.end), s.end, CaptureSource.DwellEnd, at)
      val next = stays.getOrNull(i + 1)?.takeIf { it.start <= now } ?: return@forEachIndexed
      val kind = if (next.start - s.end >= VEHICLE_GAP_MS) ActivityKind.IN_VEHICLE else ActivityKind.WALKING
      out += activity(stamp, kind, true, s.end)
      out += activity(stamp, kind, false, next.start)
    }
    for ((d, p) in plans) {
      val base = dayStart(d, tz)
      p.runs.filter { base + it.first * MIN_MS <= now }.forEach { (from, to) ->
        out += activity(stamp, ActivityKind.RUNNING, true, base + from * MIN_MS)
        if (base + to * MIN_MS <= now) out += activity(stamp, ActivityKind.RUNNING, false, base + to * MIN_MS)
      }
      // calendar reads the whole day ahead, as the real CalendarReader does
      p.appts.forEach { a ->
        out += CaptureEvent("${stamp}calendar-${d}-${a.start}", base + a.start * MIN_MS, CaptureSource.CalendarEntry(a.title), endUtcMs = base + a.end * MIN_MS)
      }
    }
    return out
  }

  private fun activity(stamp: String, kind: ActivityKind, entering: Boolean, at: Long) =
    CaptureEvent(stamp + CaptureIds.activity(kind, entering, at), at, CaptureSource.ActivityTransition(kind, entering))

  /** Core facts, standing instruction and intentions, all typed by the user. Returns (core facts, intentions). */
  private fun writeLongTerm(start: Long): Pair<Int, Int> {
    val core = listOf(
      CoreCategories.YOU to "ชอบกาแฟไม่หวาน",
      CoreCategories.YOU to "แพ้กุ้ง",
      CoreCategories.TOPICS to "สุขภาพ · ออกกำลังกาย",
      CoreCategories.TOPICS to "อยากวิ่งมินิมาราธอนให้ได้ภายในปีนี้",
      CoreCategories.AREAS to "งานฝ่ายขาย ดูแลลูกค้าองค์กร",
      CoreCategories.PROJECTS to "ทริปเชียงใหม่กับเพื่อนเดือนธันวาคม",
    )
    core.forEachIndexed { i, (cat, text) -> memory.writeCoreFact(CoreFactInput(cat, text, Provenance.USER_TYPED, start + (i + 1) * HOUR_MS * 20)) }
    memory.addStanding(NoteInput("เรียกฉันว่ามิ้นท์ และสรุปตอนเย็นให้สั้น อ่านจบในหนึ่งนาที", Provenance.USER_TYPED, start + HOUR_MS))
    val intentions = listOf("ส่งใบเสนอราคาใหม่ให้ลูกค้าภายในวันศุกร์", "จองตั๋วเครื่องบินไปเชียงใหม่ก่อนสิ้นเดือน")
    intentions.forEachIndexed { i, t -> memory.addIntention(NoteInput(t, Provenance.USER_TYPED, start + (DAYS - 2 + i) * DAY_MS)) }
    return core.size to intentions.size
  }

  private fun dayStart(day: String, tz: Long): Long = requireNotNull(MemoryFormat.parseUtc("${day}T00:00:00.000Z")) { "bad day: $day" } - tz

  private fun plusDays(day: String, n: Int): String = MemoryFormat.dayOf(dayStart(day, 0) + n * DAY_MS)

  /** Monday = 0 (epoch day 0 was a Thursday). */
  private fun weekday(day: String): Int = ((dayStart(day, 0) / DAY_MS + 3).mod(7L)).toInt()

  companion object {
    const val PREFIX = "demo-"
    const val NICKNAME = "มิ้นท์"
    const val DAYS = 14
    private const val FIRST_EVENING_MIN = 210
    private const val VEHICLE_GAP_MS = 10 * 60_000L
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    internal fun hhmm(minute: Int): String = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
  }
}
