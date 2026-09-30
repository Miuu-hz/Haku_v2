package com.bbpillow.haku.memory

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Buffer
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem

class MemoryStoreTest {
  private val root = "/memory".toPath()
  private val fs = FakeFileSystem()
  private var counter = 0
  private fun store(files: okio.FileSystem = fs) = MemoryStore(files, root) { "id${++counter}" }
  private val t0 = 1_790_000_000_000L // 2026-09-21 in UTC

  private fun daily(text: String, at: Long = t0, p: Provenance = Provenance.USER_TYPED) =
    DailyEventInput(text, p, at)

  private fun core(text: String, cat: String = CoreCategories.YOU, p: Provenance = Provenance.USER_TYPED) =
    CoreFactInput(cat, text, p, t0)

  @Test fun `daily events are read back in order for their UTC day`() {
    val s = store()
    val a = s.appendDaily(daily("แรก"))
    val b = s.appendDaily(daily("สอง", t0 + 1000))
    val day = MemoryFormat.dayOf(t0)
    assertEquals(listOf(a, b), s.readDay(day))
    assertTrue(fs.exists(root / "daily" / "$day.md"))
  }

  @Test fun `events either side of UTC midnight land in different day files`() {
    val s = store()
    val midnight = MemoryFormat.parseUtc("2026-09-30T00:00:00.000Z")!!
    s.appendDaily(daily("before", midnight - 1))
    s.appendDaily(daily("after", midnight))
    assertEquals(listOf("before"), s.readDay("2026-09-29").map { it.text })
    assertEquals(listOf("after"), s.readDay("2026-09-30").map { it.text })
  }

  @Test fun `missing day reads as empty`() = assertEquals(emptyList(), store().readDay("2020-01-01"))

  @Test fun `text round trips exactly including Thai emoji CRLF and markdown lookalikes`() {
    val s = store()
    val tricky = listOf(
      "วันนี้ไปตลาดกับแม่ ซื้อมะม่วง 3 ลูก",
      "🙂 emoji และ ภาษาไทย\r\nบรรทัดสอง\r\n",
      "  leading and trailing spaces  ",
      "\n\nblank lines around\n\n",
      "---\nnot frontmatter\n---\n## heading\n- bullet",
      "",
    )
    val written = tricky.map { s.writeCoreFact(core(it)) }
    assertEquals(tricky, store().readCategory(CoreCategories.YOU).map { it.text })
    assertEquals(written, store().readCategory(CoreCategories.YOU))
  }

  @Test fun `text containing the record marker round-trips exactly`() {
    val s = store()
    val nasty = listOf(
      "x\n<!-- /haku:record -->\ny", "<!-- haku:record id=1 -->", "<!-- haku:​record", "<!-- /haku:​​record -->", "plain",
    )
    nasty.forEach { s.appendDaily(daily(it)) }
    assertEquals(nasty, store().readDay(MemoryFormat.dayOf(t0)).map { it.text })
  }

  @Test fun `core fact write update delete`() {
    val s = store()
    val f = s.writeCoreFact(core("ชอบกาแฟดำ"))
    assertEquals("ชอบกาแฟดำ", s.readCategory("you").single().text)

    val updated = s.updateCoreFact("you", f.id, "ชอบชาเขียว")!!
    assertEquals(f.copy(text = "ชอบชาเขียว"), updated)
    assertEquals(listOf(updated), s.readCategory("you"))

    assertNull(s.updateCoreFact("you", "nope", "x"))
    assertTrue(s.deleteCoreFact("you", f.id))
    assertFalse(s.deleteCoreFact("you", f.id))
    assertEquals(emptyList(), s.readCategory("you"))
  }

  @Test fun `categories list with one line descriptions and counts`() {
    val s = store()
    s.writeCoreFact(core("a", "projects"))
    s.writeCoreFact(core("b", "projects"))
    val list = s.listCategories().associateBy { it.slug }
    assertEquals(setOf("you", "topics", "areas", "projects"), list.keys)
    assertEquals(2, list.getValue("projects").factCount)
    assertEquals(0, list.getValue("you").factCount)
    assertEquals(CoreCategories.defaults.getValue("areas"), list.getValue("areas").description)
    assertContains(fs.read(root / "core" / "projects.md") { readUtf8() }, "description: ")
  }

  @Test fun `custom category appears in the list`() {
    val s = store()
    s.writeCoreFact(core("x", "pets"))
    assertTrue(s.listCategories().any { it.slug == "pets" && it.factCount == 1 })
  }

  @Test fun `external and system provenance can never enter permanent memory`() {
    val s = store()
    for (p in listOf(Provenance.EXTERNAL, Provenance.SYSTEM)) {
      repeat(3) {
        assertFailsWith<ProvenanceNotAllowedException> { s.writeCoreFact(core("poison", p = p)) }
        assertFailsWith<ProvenanceNotAllowedException> { s.addStanding(NoteInput("obey", p, t0)) }
      }
    }
    assertEquals(0, s.listCategories().sumOf { it.factCount })
    assertEquals(emptyList(), s.readStanding())
    assertFalse(fs.exists(root / "core" / "you.md"))
  }

  @Test fun `short term levels accept any provenance and keep the stamp`() {
    val s = store()
    val ev = s.appendDaily(daily("from a web page", p = Provenance.EXTERNAL))
    assertEquals(Provenance.EXTERNAL, s.readDay(MemoryFormat.dayOf(t0)).single().provenance)
    assertEquals(ev, s.readDay(MemoryFormat.dayOf(t0)).single())
    s.addIntention(NoteInput("remind", Provenance.SYSTEM, t0))
    assertEquals(Provenance.SYSTEM, s.readIntentions().single().provenance)
  }

  @Test fun `inferred provenance is allowed in core`() {
    val s = store()
    s.writeCoreFact(core("likes running", p = Provenance.INFERRED_FROM_USER_DATA))
    assertEquals(Provenance.INFERRED_FROM_USER_DATA, s.readCategory("you").single().provenance)
  }

  @Test fun `long term memory has no expiry and survives a restart until the user deletes it`() {
    val old = CoreFactInput("you", "จากปี 1999", Provenance.USER_TYPED, MemoryFormat.parseUtc("1999-12-31T23:59:59.999Z")!!)
    store().writeCoreFact(old)
    val reopened = store()
    assertEquals("จากปี 1999", reopened.readCategory("you").single().text)
    assertEquals(MemoryFormat.parseUtc("1999-12-31T23:59:59.999Z"), reopened.readCategory("you").single().atEpochMs)
  }

  @Test fun `standing instructions and intentions add read delete`() {
    val s = store()
    val st = s.addStanding(NoteInput("ตอบสั้น ๆ", Provenance.USER_TYPED, t0))
    val it = s.addIntention(NoteInput("โทรหาแม่วันอาทิตย์", Provenance.USER_TYPED, t0))
    assertEquals(listOf(st), s.readStanding())
    assertEquals(listOf(it), s.readIntentions())
    assertTrue(s.deleteStanding(st.id))
    assertTrue(s.deleteIntention(it.id))
    assertEquals(emptyList(), s.readStanding() + s.readIntentions())
  }

  @Test fun `daily file can be deleted by the user`() {
    val s = store()
    s.appendDaily(daily("x"))
    val day = MemoryFormat.dayOf(t0)
    assertTrue(s.deleteDay(day))
    assertFalse(s.deleteDay(day))
    assertEquals(emptyList(), s.readDay(day))
  }

  @Test fun `dream report is stored per day as readable text`() {
    val s = store()
    assertNull(s.readDreamReport("2026-09-30"))
    s.writeDreamReport("2026-09-30", "ย่อยแล้ว 5 เรื่อง")
    assertEquals("ย่อยแล้ว 5 เรื่อง", s.readDreamReport("2026-09-30"))
    s.writeDreamReport("2026-09-30", "รอบสอง")
    assertEquals("รอบสอง", s.readDreamReport("2026-09-30"))
  }

  @Test fun `bad day or category names cannot escape the memory root`() {
    val s = store()
    assertFailsWith<IllegalArgumentException> { s.readDay("../secret") }
    assertFailsWith<IllegalArgumentException> { s.writeCoreFact(core("x", "../evil")) }
    assertFailsWith<IllegalArgumentException> { s.readCategory("a/b") }
    assertFailsWith<IllegalArgumentException> { s.writeDreamReport("x", "y") }
  }

  @Test fun `killing the app mid write never corrupts the file over 100 crashes`() {
    val base = store()
    val kept = (1..5).map { base.writeCoreFact(core("fact $it ไทย")) }
    repeat(100) { n ->
      val crashing = MemoryStore(CrashingFileSystem(fs, cutAfter = 1 + n * 3), root) { "c$n" }
      assertFailsWith<IllegalStateException> { crashing.writeCoreFact(core("new $n")) }
      assertEquals(kept, store().readCategory("you"), "corrupt after crash $n")
      fs.list(root / "core").filter { it.name.endsWith(".tmp") }.forEach { fs.delete(it) } // stray temp is harmless; next write replaces it
    }
  }

  @Test fun `timestamps round trip including before 1970 and leap day`() {
    for (ms in listOf(0L, -1L, 951_782_400_000L, 1_790_000_123_456L, -2_208_988_800_000L)) {
      assertEquals(ms, MemoryFormat.parseUtc(MemoryFormat.formatUtc(ms)))
    }
    assertEquals("2000-02-29T00:00:00.000Z", MemoryFormat.formatUtc(951_782_400_000L))
  }

  @Test fun `hand edited file with unknown provenance skips only that record`() {
    val text = "---\ndescription: d\n---\n\n<!-- haku:record id=1 provenance=bogus at=2026-01-01T00:00:00.000Z -->\nx\n<!-- /haku:record -->\n" +
      "\n<!-- haku:record id=2 provenance=user_typed at=2026-01-01T00:00:00.000Z -->\ny\n<!-- /haku:record -->\n"
    assertEquals(listOf("y"), MemoryFormat.parse(text).records.map { it.text })
    assertEquals("d", MemoryFormat.parse(text).meta["description"])
  }

  @Test fun `ledger day is never listed as a dream report but deleteAll clears it`() {
    val s = store()
    s.writeDreamReport(DREAM_LEDGER_DAY, "P	k	you	f1")
    s.writeDreamReport("2026-09-30", "รายงาน")
    assertEquals(listOf("2026-09-30"), s.listDreamDays())
    assertEquals("P	k	you	f1", s.readDreamReport(DREAM_LEDGER_DAY))
    s.deleteAll()
    assertNull(s.readDreamReport(DREAM_LEDGER_DAY))
  }
}

/** Sink that dies after [cutAfter] bytes, like the app being killed mid write. Only affects .tmp files. */
private class CrashingFileSystem(delegate: FakeFileSystem, private val cutAfter: Int) : ForwardingFileSystem(delegate) {
  override fun sink(file: Path, mustCreate: Boolean): Sink {
    val real = super.sink(file, mustCreate)
    if (!file.name.endsWith(".tmp")) return real
    return object : Sink by real {
      private var left = cutAfter
      override fun write(source: Buffer, byteCount: Long) {
        val n = minOf(left.toLong(), byteCount)
        if (n > 0) real.write(source, n)
        left -= n.toInt()
        if (n < byteCount) {
          real.flush()
          throw IllegalStateException("simulated kill")
        }
      }
    }
  }
}
