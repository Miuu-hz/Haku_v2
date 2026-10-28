package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeWordsTest {
  private val now = MemoryFormat.parseUtc("2026-09-30T03:00:00.000Z")!! // 10:00 in UTC+7
  private fun iso(ms: Long) = MemoryFormat.formatUtc(ms)

  @Test fun `yesterday is the local day before, expressed in UTC`() {
    val r = TimeWords.find("เมื่อวานไปไหนมา", now, 420)!!
    assertEquals("2026-09-28T17:00:00.000Z", iso(r.fromEpochMs))
    assertEquals("2026-09-29T17:00:00.000Z", iso(r.toEpochMs))
    assertEquals(listOf("2026-09-28", "2026-09-29"), TimeWords.utcDays(r))
  }

  @Test fun `longest word wins so day before yesterday is not yesterday`() {
    val r = TimeWords.find("เมื่อวานซืนทำอะไร", now, 420)!!
    assertEquals("เมื่อวานซืน", r.word)
    assertEquals("2026-09-27T17:00:00.000Z", iso(r.fromEpochMs))
  }

  @Test fun `today and week ranges`() {
    assertEquals("2026-09-29T17:00:00.000Z", iso(TimeWords.find("วันนี้", now, 420)!!.fromEpochMs))
    val week = TimeWords.find("สัปดาห์นี้", now, 420)!!
    assertEquals("2026-09-23T17:00:00.000Z", iso(week.fromEpochMs))
    assertEquals("2026-09-30T17:00:00.000Z", iso(week.toEpochMs))
  }

  @Test fun `no date word means no range`() = assertNull(TimeWords.find("ครั้งล่าสุดที่ไปร้านกาแฟ", now, 420))

  @Test fun `offset changes which day now belongs to`() {
    // 20:00 UTC on the 30th is already the 1st of October in Thailand
    val late = MemoryFormat.parseUtc("2026-09-30T20:00:00.000Z")!!
    assertEquals("2026-09-30T17:00:00.000Z", iso(TimeWords.find("วันนี้", late, 420)!!.fromEpochMs))
    assertEquals("2026-09-30T00:00:00.000Z", iso(TimeWords.find("วันนี้", late, 0)!!.fromEpochMs))
  }

  // now is Wednesday 2026-09-30 (local)
  private fun dayOf(q: String) = TimeWords.find(q, now, 420)!!.let { r ->
    assertEquals(r.fromEpochMs + 86_400_000L, r.toEpochMs, "one day")
    MemoryFormat.formatUtc(r.fromEpochMs + 420 * 60_000L).substring(0, 10)
  }

  @Test fun `a bare weekday is the most recent one, never the future`() {
    assertEquals("2026-09-26", dayOf("วันเสาร์ไปวิ่งที่ไหน"))
    assertEquals("2026-09-27", dayOf("วันอาทิตย์ไปไหน"))
    assertEquals("2026-09-24", dayOf("วันพฤหัสบดีมีนัดอะไร"))
    assertEquals("2026-09-29", dayOf("วันอังคารไปไหน"))
  }

  @Test fun `today's own weekday means today, with ที่แล้ว it means a week back`() {
    assertEquals("2026-09-30", dayOf("วันพุธไปไหน"))
    assertEquals("2026-09-23", dayOf("วันพุธที่แล้วไปไหน"))
    assertEquals("2026-09-26", dayOf("เสาร์ที่แล้วไปไหน"))
    assertEquals("2026-09-27", dayOf("วันอาทิตย์ที่แล้วไปไหน"))
  }

  @Test fun `อาทิตย์ without วัน is still a week`() {
    val r = TimeWords.find("อาทิตย์ที่แล้วไปไหน", now, 420)!!
    assertEquals("2026-09-16T17:00:00.000Z", iso(r.fromEpochMs))
    assertEquals("2026-09-23T17:00:00.000Z", iso(r.toEpochMs))
  }

  @Test fun `part of day narrows the window of the day word, alone it means today`() {
    val r = TimeWords.find("เมื่อวานช่วงเย็นอยู่ที่ไหน", now, 420)!!
    assertEquals("2026-09-28T17:00:00.000Z", iso(r.fromEpochMs))
    assertTrue(r.inWindow(MemoryFormat.parseUtc("2026-09-29T11:00:00.000Z")!!)) // 18:00 local
    assertFalse(r.inWindow(MemoryFormat.parseUtc("2026-09-29T02:00:00.000Z")!!)) // 09:00 local
    for (q in listOf("ตอนเช้าไปไหนมา", "เช้านี้ไปไหน", "ตอนบ่ายทำอะไร", "ตอนกลางคืนอยู่ไหน")) {
      val t = TimeWords.find(q, now, 420)!!
      assertEquals("2026-09-29T17:00:00.000Z", iso(t.fromEpochMs), q)
      assertTrue(t.window != null, q)
    }
    assertNull(TimeWords.find("วันนี้", now, 420)!!.window)
  }
}
