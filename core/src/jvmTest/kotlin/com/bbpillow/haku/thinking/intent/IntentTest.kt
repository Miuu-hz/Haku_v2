package com.bbpillow.haku.thinking.intent

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.ActivityKind
import com.bbpillow.haku.capture.CalendarRowInput
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureRecorder
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.WakeTrigger
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.index.DictionarySegmenter
import com.bbpillow.haku.surface.chat.IntentCommand
import com.bbpillow.haku.surface.intent.IntentListPresenter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private val VOCAB = setOf(
  "ที่", "ทำงาน", "ส่ง", "รายงาน", "บ้าน", "กิน", "ยา", "นัด", "หมอ", "ฟัน", "ประชุม", "ทีม", "กาแฟ", "ลูกค้า", "ใบเสนอราคา",
  "ยิม", "ร้าน", "วันเกิด", "แม่", "ซื้อ", "ของขวัญ", "ออกกำลังกาย", "ดื่ม", "น้ำ", "ห้าง", "เซ็นทรัล", "ถ่าน", "สนามบิน",
  "ออฟฟิศ", "สีลม", "กับ", "เรื่อง", "วันนี้", "ไป", "ตลาด", "ผัก", "โทร", "หา", "ลด", "น้ำตาล", "ยืด", "เส้น", "เตรียม", "สไลด์",
  "เอา", "บัตร", "ประกัน", "ด้วย", "พาสปอร์ต", "ร้อน", "เหนื่อย", "ของ", "ฉัน",
)

private class FakePlaces(var places: List<PlaceRecord> = emptyList()) : PlaceRepository {
  override fun list() = places
  override fun addNew(places: List<PlaceRecord>) = 0
  override fun confirm(id: String, label: String, category: PlaceCategory) = false
  override fun delete(id: String) = false
  override fun deleteAll() {}
}

class IntentTest {
  private val pipeline = TextPipeline(DictionarySegmenter(VOCAB))
  private val matcher = IntentMatcher(pipeline)
  private val fs = FakeFileSystem()
  private var n = 0
  private val memory = MemoryStore(fs, "/m".toPath()) { "i${++n}" }
  private val office = PlaceRecord("p-work", 13.73, 100.56, label = "ออฟฟิศสีลม", category = PlaceCategory.WORK, confirmed = true)
  private val home = PlaceRecord("p-home", 13.75, 100.50, label = null, category = PlaceCategory.HOME, confirmed = true)
  private val places = FakePlaces(listOf(office, home))
  private val service = IntentService(memory, places, pipeline)
  private val t0 = 1_790_000_000_000L
  private val hour = 3_600_000L
  private val day = 24 * hour
  private val db = BundledSQLiteDriver().open(":memory:")

  @AfterTest fun close() = db.close()

  // parser

  private data class P(val scope: IntentScope, val trigger: String, val reminder: String, val max: Int = 1)

  @Test fun `parser reads Thai condition-reminder phrasings`() {
    val cases = mapOf(
      "ถ้าไปถึงที่ทำงานให้เตือนส่งรายงาน" to P(IntentScope.PLACE, "ที่ทำงาน", "ส่งรายงาน"),
      "เมื่อถึงที่ทำงาน เตือนให้โทรหาแม่" to P(IntentScope.PLACE, "ที่ทำงาน", "โทรหาแม่"),
      "พอถึงบ้านแล้วเตือนให้กินยา" to P(IntentScope.PLACE, "บ้าน", "กินยา"),
      "ถ้าไปห้างเซ็นทรัล บอกให้ซื้อถ่าน" to P(IntentScope.PLACE, "ห้างเซ็นทรัล", "ซื้อถ่าน"),
      "ถ้ามีนัดกับหมอฟัน ให้บอกว่าเอาบัตรประกันไปด้วย" to P(IntentScope.APPOINTMENT, "หมอฟัน", "เอาบัตรประกันไปด้วย"),
      "ถ้ามีประชุมทีม ช่วยเตือนให้เตรียมสไลด์" to P(IntentScope.APPOINTMENT, "ประชุมทีม", "เตรียมสไลด์"),
      "ถ้าจดเรื่องออกกำลังกาย ให้บอกว่าดื่มน้ำ" to P(IntentScope.NOTE, "ออกกำลังกาย", "ดื่มน้ำ"),
      "ถ้าเจอเรื่องลูกค้า ให้เตือนเรื่องใบเสนอราคา" to P(IntentScope.ANY, "ลูกค้า", "เรื่องใบเสนอราคา"),
      "เมื่อไหร่ที่พูดถึงกาแฟ เตือนว่าลดน้ำตาล" to P(IntentScope.ANY, "กาแฟ", "ลดน้ำตาล"),
      "ถ้าเจอคำว่าวันเกิด เตือนซื้อของขวัญ" to P(IntentScope.ANY, "วันเกิด", "ซื้อของขวัญ"),
      "ถ้าถึงยิมให้เตือนยืดเส้นทุกครั้ง" to P(IntentScope.PLACE, "ยิม", "ยืดเส้น", max = 20),
      "หากไปสนามบินให้เตือนเอาพาสปอร์ต 3 ครั้ง" to P(IntentScope.PLACE, "สนามบิน", "เอาพาสปอร์ต", max = 3),
    )
    for ((text, want) in cases) {
      val got = assertNotNull(IntentParser.parse(text), text)
      assertEquals(want, P(got.scope, got.trigger, got.reminder, got.maxFires), text)
    }
  }

  @Test fun `parser reads expiry and leaves ordinary questions alone`() {
    assertEquals(7 * day, IntentParser.parse("ถ้าถึงบ้านให้เตือนกินยา ภายใน 7 วัน")?.expiresInMs)
    assertEquals(Intention.DEFAULT_TTL_MS, IntentParser.parse("ถ้าถึงบ้านให้เตือนกินยา")?.expiresInMs)
    listOf(
      "เมื่อวานไปไหนมา",
      "ถ้าฉันบอกว่าเหนื่อยต้องทำยังไง",
      "ถ้าร้อนควรเตือนอะไร",
      "เตือนอะไรไว้บ้าง",
      "สรุปวันนี้ให้หน่อย",
    ).forEach { assertNull(IntentParser.parse(it), it) }
  }

  // matcher

  private fun intention(
    trigger: String,
    scope: IntentScope = IntentScope.ANY,
    max: Int = 1,
    cooldown: Long = Intention.DEFAULT_COOLDOWN_MS,
    fired: Int = 0,
    last: Long? = null,
    placeId: String? = null,
  ) = Intention("x", trigger, "r", scope, max, cooldown, t0 + 30 * day, t0, fired, last, placeId)

  private fun ev(scope: IntentScope, text: String, at: Long = t0 + hour, placeId: String? = null) = IntentTriggerEvent(scope, text, at, placeId)

  @Test fun `matcher needs every trigger word and the right scope`() {
    val i = intention("ประชุมทีม", IntentScope.APPOINTMENT)
    assertEquals(1, matcher.match(listOf(i), ev(IntentScope.APPOINTMENT, "ประชุมทีม วันนี้")).size)
    assertTrue(matcher.match(listOf(i), ev(IntentScope.APPOINTMENT, "ประชุม")).isEmpty(), "one word missing")
    assertTrue(matcher.match(listOf(i), ev(IntentScope.NOTE, "ประชุมทีม")).isEmpty(), "wrong scope")
    assertEquals(1, matcher.match(listOf(intention("กาแฟ")), ev(IntentScope.NOTE, "10:00 ร้านกาแฟ")).size, "ANY takes notes")
    assertTrue(matcher.match(listOf(intention("ยา")), ev(IntentScope.NOTE, "ไปตลาดซื้อผัก")).isEmpty())
    assertTrue(matcher.match(listOf(intention("ที่")), ev(IntentScope.NOTE, "ที่ตลาด")).isEmpty(), "filler alone never fires")
  }

  @Test fun `matcher respects cooldown, max fires and expiry`() {
    val base = intention("กาแฟ", max = 3, cooldown = 6 * hour)
    assertTrue(matcher.match(listOf(base.copy(fired = 1, lastFiredAtMs = t0)), ev(IntentScope.NOTE, "กาแฟ", t0 + hour)).isEmpty(), "cooling down")
    assertEquals(1, matcher.match(listOf(base.copy(fired = 1, lastFiredAtMs = t0)), ev(IntentScope.NOTE, "กาแฟ", t0 + 7 * hour)).size)
    assertTrue(matcher.match(listOf(base.copy(fired = 3, lastFiredAtMs = t0)), ev(IntentScope.NOTE, "กาแฟ", t0 + 2 * day)).isEmpty(), "used up")
    assertTrue(matcher.match(listOf(base), ev(IntentScope.NOTE, "กาแฟ", t0 + 31 * day)).isEmpty(), "expired")
  }

  @Test fun `a place-pinned intention fires on that place only`() {
    val i = intention("ที่ทำงาน", IntentScope.PLACE, placeId = "p-work")
    assertEquals(1, matcher.match(listOf(i), ev(IntentScope.PLACE, "ชื่อใหม่", placeId = "p-work")).size)
    assertTrue(matcher.match(listOf(i), ev(IntentScope.PLACE, "ที่ทำงาน", placeId = "p-other")).isEmpty())
  }

  // store

  @Test fun `intentions round-trip through INTENTIONS md and counters persist`() {
    val saved = assertNotNull(service.add(assertNotNull(IntentParser.parse("ถ้ามีนัดหมอฟันให้เตือนเอาบัตรประกัน 2 ครั้ง")), t0))
    assertEquals(saved, service.all().single().second)
    assertTrue(fs.read("/m/INTENTIONS.md".toPath()) { readUtf8() }.contains("เมื่อ: หมอฟัน\nเตือน: เอาบัตรประกัน\n"))
    assertEquals(Provenance.USER_TYPED, memory.readIntentions().single().provenance)

    assertEquals(1, service.fire(ev(IntentScope.APPOINTMENT, "นัดหมอฟัน", t0 + day)).size)
    val after = assertNotNull(service.all().single().second)
    assertEquals(1, after.fired)
    assertEquals(t0 + day, after.lastFiredAtMs)
    assertEquals(saved.id, after.id)

    assertTrue(service.cancel(saved.id))
    assertTrue(service.all().isEmpty())
  }

  @Test fun `old free-text intentions are listed but never fire`() {
    memory.addIntention(NoteInput("ส่งใบเสนอราคาให้ลูกค้า", Provenance.USER_TYPED, t0))
    assertNull(service.all().single().second)
    assertTrue(service.onNote("ลูกค้า ใบเสนอราคา", t0 + hour).isEmpty())
    val list = IntentListPresenter(service, 420) { t0 + hour }.load().items.single()
    assertEquals("ส่งใบเสนอราคาให้ลูกค้า", list.title)
  }

  @Test fun `a place trigger pins the one confirmed place it names`() {
    assertEquals("p-work", service.add(IntentionInput("ที่ทำงาน", "ส่งรายงาน", IntentScope.PLACE), t0)?.placeId)
    assertNull(service.add(IntentionInput("ห้าง", "ซื้อถ่าน", IntentScope.PLACE), t0)?.placeId)
    assertNull(service.add(IntentionInput("ไป", "x", IntentScope.PLACE), t0), "no real word to match on")
  }

  @Test fun `chat command stores and confirms, other questions pass through`() {
    val cmd = IntentCommand(service)
    val reply = assertNotNull(cmd.tryHandle("ถ้าไปถึงที่ทำงานให้เตือนส่งรายงาน", t0))
    assertTrue(reply.text.startsWith("จะเตือน “ส่งรายงาน” เมื่อถึงที่ทำงาน"), reply.text)
    assertNull(cmd.tryHandle("เมื่อวานไปไหนมา", t0))
    assertEquals(IntentCommand.UNCLEAR, cmd.tryHandle("ถ้าเจอเรื่องของฉันให้เตือนกินยา", t0)?.text)
  }

  // capture hook end to end

  @Test fun `new stays and appointments reach the service once`() {
    val fired = mutableListOf<IntentFireOutput>()
    val recorder = CaptureRecorder(CaptureEventStore(db)) { fired += service.onCapture(it, t0) }
    service.add(assertNotNull(IntentParser.parse("ถ้าถึงที่ทำงานให้เตือนส่งรายงาน")), t0)
    service.add(assertNotNull(IntentParser.parse("ถ้ามีประชุมทีมให้เตือนเตรียมสไลด์")), t0)

    val still = listOf(WakeTrigger.Activity(t0, ActivityKind.STILL, true))
    recorder.onWake(still, EventPlace(office.lat + 0.0005, office.lon), t0) // ~55 m from the office
    recorder.onWake(still, EventPlace(office.lat + 0.0005, office.lon), t0) // retried broadcast: nothing new
    assertEquals(listOf("อย่าลืม: ส่งรายงาน"), fired.map { it.title })

    val rows = listOf(CalendarRowInput(1, t0 + 2 * hour, t0 + 3 * hour, "ประชุมทีม"))
    recorder.recordCalendar(rows, t0 - day, t0 + day)
    recorder.recordCalendar(rows, t0 - day, t0 + day) // re-read: not new
    assertEquals(listOf("อย่าลืม: ส่งรายงาน", "อย่าลืม: เตรียมสไลด์"), fired.map { it.title })
  }

  /** P5.3 pass bar: 10 intentions over a scripted week, >80% fire when due and none over its quota or off-condition. */
  @Test fun `ten intentions over a week fire on cue and never misfire`() {
    val phrases = listOf(
      "ถ้าถึงที่ทำงานให้เตือนส่งรายงาน", "พอถึงบ้านแล้วเตือนให้กินยา ทุกวัน", "ถ้ามีนัดหมอฟันให้เตือนเอาบัตรประกัน",
      "ถ้ามีประชุมทีมให้เตือนเตรียมสไลด์ 2 ครั้ง", "ถ้าจดเรื่องออกกำลังกายให้บอกว่าดื่มน้ำ", "ถ้าเจอเรื่องลูกค้าให้เตือนใบเสนอราคา",
      "เมื่อพูดถึงกาแฟ เตือนว่าลดน้ำตาล", "ถ้าเจอคำว่าวันเกิด เตือนซื้อของขวัญ", "ถ้าไปห้างเซ็นทรัลให้เตือนซื้อถ่าน",
      "ถ้าไปสนามบินให้เตือนเอาพาสปอร์ต",
    )
    val ids = phrases.map { assertNotNull(service.add(assertNotNull(IntentParser.parse(it), it), t0), it).id }
    val central = PlaceRecord("p-c", 13.74, 100.54, label = "ห้างเซ็นทรัล", confirmed = true)
    places.places = places.places + central
    fun arrive(p: PlaceRecord, at: Long) =
      service.onCapture(listOf(CaptureEvent("d$at", at, CaptureSource.DwellStart, EventPlace(p.lat, p.lon))), at)
    fun meet(title: String, at: Long) =
      service.onCapture(listOf(CaptureEvent("c$at", at, CaptureSource.CalendarEntry(title), endUtcMs = at + hour)), at)

    val log = mutableListOf<IntentFireOutput>()
    for (d in 0 until 7) {
      val base = t0 + d * day
      log += arrive(office, base + 2 * hour)
      log += meet("ประชุมทีม", base + 3 * hour)
      log += service.onNote("ไปตลาดซื้อผัก", base + 4 * hour) // matches nothing
      log += arrive(home, base + 11 * hour)
    }
    log += meet("นัดหมอฟัน", t0 + hour)
    log += service.onNote("วันนี้ออกกำลังกาย", t0 + 5 * hour)
    log += service.onNote("คุยกับลูกค้า", t0 + 6 * hour)
    log += service.onNote("ร้านกาแฟ", t0 + 7 * hour)
    log += service.onNote("วันเกิดแม่", t0 + 8 * hour)
    log += arrive(central, t0 + 9 * hour)
    // the airport intention's condition never comes up

    val byId = log.groupingBy { it.intentionId }.eachCount()
    val stored = service.all().associate { it.first.id to it.second!! }
    stored.values.forEach { assertTrue(it.fired <= it.maxFires, "${it.trigger} over quota") }
    assertEquals(stored.mapValues { it.value.fired }.filterValues { it > 0 }, byId, "every fire written back")
    val expected = mapOf(ids[0] to 1, ids[1] to 7, ids[2] to 1, ids[3] to 2, ids[4] to 1, ids[5] to 1, ids[6] to 1, ids[7] to 1, ids[8] to 1)
    assertEquals(expected, byId)
    assertTrue(ids[9] !in byId, "no misfire")
  }
}
