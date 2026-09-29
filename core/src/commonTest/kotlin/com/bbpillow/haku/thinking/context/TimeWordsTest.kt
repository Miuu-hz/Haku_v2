package com.bbpillow.haku.thinking.context

import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
