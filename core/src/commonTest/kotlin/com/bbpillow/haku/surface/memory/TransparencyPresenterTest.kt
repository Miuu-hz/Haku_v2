package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.capture.diary.FakeEvents
import com.bbpillow.haku.capture.diary.FakeLedger
import com.bbpillow.haku.capture.diary.FakePlaces
import com.bbpillow.haku.thinking.summary.FakeSummaries
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.network.ConnectionPurpose
import com.bbpillow.haku.network.ConnectionRecord
import com.bbpillow.haku.network.ConnectionRepository
import com.bbpillow.haku.surface.onboarding.ProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class TransparencyPresenterTest {
  private var n = 0
  private val memory = MemoryStore(FakeFileSystem(), "/mem".toPath()) { "f${++n}" }
  private val sep23 = 1_790_121_600_000L // 2026-09-23 00:00 UTC
  private val connections = FakeConnections()
  private val forget = ForgetEverything(FakeEvents(emptyList()), FakePlaces(emptyList()), FakeLedger(), FakeSummaries(), memory, ProfileStore(FakeFileSystem(), "/p.txt".toPath()), connections)
  private val presenter = TransparencyPresenter(memory, forget, connections, 420) { sep23 }

  @Test fun categoriesAreThaiWithCountsAndProvenance() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "ชอบกาแฟไม่หวาน", Provenance.USER_TYPED, sep23))
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "เข้างานราว 08:45", Provenance.INFERRED_FROM_USER_DATA, sep23))
    val state = presenter.load()
    assertEquals(listOf("ตัวคุณ", "เรื่องที่สนใจ", "ด้านของชีวิต", "โครงการ"), state.categories.map { it.title })
    val you = state.categories.first()
    assertEquals(2, you.count)
    assertEquals(MemoryFact(CoreCategories.YOU, "f1", "ชอบกาแฟไม่หวาน", "23 ก.ย.", Provenance.USER_TYPED), you.facts[0])
    assertEquals(0, state.egressToday)
  }

  @Test fun forgetRemovesTheFactForGood() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.TOPICS, "สุขภาพ", Provenance.USER_TYPED, sep23))
    val fact = presenter.load().categories[1].facts.single()
    val after = presenter.forget(fact)
    assertEquals(0, after.categories[1].count)
    assertTrue(TransparencyPresenter(memory, forget, connections, 420) { sep23 }.load().categories.all { it.facts.isEmpty() })
  }

  @Test fun unknownCategoryFallsBackToItsSlugAndDescription() {
    memory.writeCoreFact(CoreFactInput("move-condo", "ย้ายคอนโดเดือนหน้า", Provenance.USER_TYPED, sep23))
    val c = presenter.load().categories.last()
    assertEquals("move-condo", c.title)
    assertEquals(1, c.count)
  }

  @Test fun forgetCategoryRemovesItsFileAndLeavesOthers() {
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, "ชอบกาแฟ", Provenance.USER_TYPED, sep23))
    memory.writeCoreFact(CoreFactInput(CoreCategories.TOPICS, "สุขภาพ", Provenance.USER_TYPED, sep23))
    val after = presenter.forgetCategory(CoreCategories.YOU)
    assertEquals(0, after.categories.first().count)
    assertEquals(1, after.categories[1].count)
    assertTrue(memory.readCategory(CoreCategories.YOU).isEmpty())
  }

  @Test fun exportHasEveryRecordTextExactly() {
    val texts = listOf("ชอบกาแฟไม่หวาน", "บรรทัดแรก\nบรรทัดสอง # ไม่ใช่หัวข้อ", "emoji \uD83D\uDE00 \"quoted\" `code`")
    memory.writeCoreFact(CoreFactInput(CoreCategories.YOU, texts[0], Provenance.USER_TYPED, sep23))
    memory.writeCoreFact(CoreFactInput("move-condo", texts[1], Provenance.USER_TYPED, sep23))
    memory.addStanding(NoteInput(texts[2], Provenance.USER_TYPED, sep23))
    memory.appendDaily(DailyEventInput("08:00 ออกจากบ้าน", Provenance.INFERRED_FROM_USER_DATA, sep23))
    memory.addIntention(NoteInput("โทรหาหมอ", Provenance.USER_TYPED, sep23))
    memory.writeDreamReport("2026-09-23", "รายงานความฝัน")
    val md = presenter.exportMarkdown()
    (texts + listOf("08:00 ออกจากบ้าน", "โทรหาหมอ", "รายงานความฝัน")).forEach { assertTrue(md.contains(it), it) }
    assertTrue(md.contains(Provenance.USER_TYPED.key))
  }

  @Test fun exportOfEmptyMemoryIsJustTheTitle() {
    assertEquals("# Haku: ความจำทั้งหมดของคุณ", presenter.exportMarkdown().trim())
  }

  @Test fun dreamReportsListLatestNightsFirstAndCapped() {
    for (d in 1..9) memory.writeDreamReport("2026-09-0$d", "รายงานคืน $d")
    val reports = presenter.load().dreamReports
    assertEquals(7, reports.size)
    assertEquals("2026-09-09", reports.first().day)
    assertEquals("รายงานคืน 9", reports.first().text)
    assertEquals("2026-09-03", reports.last().day)
  }

  @Test fun dreamLedgerIsNeverShownAsAReport() {
    memory.writeDreamReport(com.bbpillow.haku.memory.DREAM_LEDGER_DAY, "P	key	you	f1")
    assertTrue(presenter.load().dreamReports.isEmpty())
    memory.writeDreamReport("2026-09-23", "รายงานจริง")
    assertEquals(listOf("รายงานจริง"), presenter.load().dreamReports.map { it.text })
  }

  @Test fun connectionsShowLastThirtyDaysWithTotals() {
    val url = "https://huggingface.co/google/m/resolve/c/m.litertlm?download=true"
    connections.add(ConnectionRecord.of(sep23 - 31 * 86_400_000L, url, ConnectionPurpose.MODEL_DOWNLOAD, 9, null, "สำเร็จ"))
    connections.add(ConnectionRecord.of(sep23 - 3_600_000L, url, ConnectionPurpose.MODEL_DOWNLOAD, 2_600_000_000, null, "สำเร็จ"))
    connections.add(ConnectionRecord.of(sep23 - 60_000L, url, ConnectionPurpose.MODEL_DOWNLOAD, null, null, "หยุดไว้"))
    val state = presenter.load()
    assertEquals(
      listOf(
        ConnectionItem("23 ก.ย. 06:59", "huggingface.co", "ดาวน์โหลดโมเดล AI ลงเครื่อง", null, "หยุดไว้"),
        ConnectionItem("23 ก.ย. 06:00", "huggingface.co", "ดาวน์โหลดโมเดล AI ลงเครื่อง", "2.6 GB", "สำเร็จ"),
      ),
      state.connections,
    )
    assertEquals("30 วันล่าสุด 2 ครั้ง · รับเข้า 2.6 GB · ข้อมูลส่วนตัวที่ส่งออก 0 ไบต์", state.connectionsTotal)
    assertEquals(0, state.egressToday, "a model download sends no personal data")
  }

  @Test fun noConnectionsMeansZeroEverything() {
    assertEquals("30 วันล่าสุด 0 ครั้ง · รับเข้า 0 ไบต์ · ข้อมูลส่วนตัวที่ส่งออก 0 ไบต์", presenter.load().connectionsTotal)
  }

  @Test fun forgetEverythingClearsTheConnectionLog() {
    connections.add(ConnectionRecord.of(sep23, "https://huggingface.co/x", ConnectionPurpose.MODEL_DOWNLOAD, 1, null, "สำเร็จ"))
    assertTrue(presenter.forgetEverything().connections.isEmpty())
  }
}

internal class FakeConnections : ConnectionRepository {
  private val rows = mutableListOf<ConnectionRecord>()
  override fun add(record: ConnectionRecord) { rows += record }
  override fun since(fromEpochMs: Long) = rows.filter { it.atEpochMs >= fromEpochMs }.sortedByDescending { it.atEpochMs }
  override fun clear() = rows.clear()
}
