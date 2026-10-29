package com.bbpillow.haku.thinking.appointment

import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppointmentParserTest {
  // 2026-10-30 08:00 Asia/Bangkok (UTC+7 / 420 minutes) = 2026-10-30 01:00 UTC
  private val now = MemoryFormat.parseUtc("2026-10-30T01:00:00.000Z")!!
  private val TZ = 420

  private fun iso(ms: Long): String = MemoryFormat.formatUtc(ms)

  // --- Time Formats ---

  @Test fun `parse 10_00น with date`() {
    val draft = AppointmentParser.parse("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("ขูดหินปูน", draft.title)
    assertEquals("2026-10-31T03:00:00.000Z", iso(draft.startUtcMs))
    assertEquals("2026-10-31T04:00:00.000Z", iso(draft.endUtcMs))
  }

  @Test fun `parse 10_00 colon format`() {
    val draft = AppointmentParser.parse("นัดคุยงาน 10:00 วันนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("คุยงาน", draft.title)
    assertEquals("2026-10-30T03:00:00.000Z", iso(draft.startUtcMs))
    assertEquals("2026-10-30T04:00:00.000Z", iso(draft.endUtcMs))
  }

  @Test fun `parse 10_30 dot format with mareun-nee`() {
    val draft = AppointmentParser.parse("มีนัดพบแพทย์ 10.30 มะรืนนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("พบแพทย์", draft.title)
    assertEquals("2026-11-01T03:30:00.000Z", iso(draft.startUtcMs))
    assertEquals("2026-11-01T04:30:00.000Z", iso(draft.endUtcMs))
  }

  @Test fun `parse bare mareun without nee`() {
    val draft = AppointmentParser.parse("มีนัดพบแพทย์ 10.30 มะรืน", now, TZ)
    assertNotNull(draft)
    assertEquals("พบแพทย์", draft.title)
    assertEquals("2026-11-01T03:30:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 10 mong`() {
    val draft = AppointmentParser.parse("มีนัด 10 โมง", now, TZ)
    assertNotNull(draft)
    assertEquals("นัด", draft.title)
    assertEquals("2026-10-30T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse sib mong word number`() {
    val draft = AppointmentParser.parse("นัดกินกาแฟสิบโมง", now, TZ)
    assertNotNull(draft)
    assertEquals("กินกาแฟ", draft.title)
    assertEquals("2026-10-30T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 9 mong chao`() {
    val draft = AppointmentParser.parse("ไปหาหมอ 9 โมงเช้า", now, TZ)
    assertNotNull(draft)
    assertEquals("ไปหาหมอ", draft.title)
    assertEquals("2026-10-30T02:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse thiang noon`() {
    val draft = AppointmentParser.parse("นัดกินข้าวเที่ยงวันนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("กินข้าว", draft.title)
    assertEquals("2026-10-30T05:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse thiang krueng 12_30 with particle`() {
    val draft = AppointmentParser.parse("นัดทานข้าวเที่ยงครึ่งพรุ่งนี้ครับ", now, TZ)
    assertNotNull(draft)
    assertEquals("ทานข้าว", draft.title)
    assertEquals("2026-10-31T05:30:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse bai 2 afternoon 14_00`() {
    val draft = AppointmentParser.parse("มีนัดประชุมบ่าย 2 พรุ่งนี้ค่ะ", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุม", draft.title)
    assertEquals("2026-10-31T07:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse bai song word`() {
    val draft = AppointmentParser.parse("ประชุมทีมขายบ่ายสองวันศุกร์", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุมทีมขาย", draft.title)
    assertEquals("2026-10-30T07:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse bai song mong`() {
    val draft = AppointmentParser.parse("นัดคุยงานบ่ายสองโมง", now, TZ)
    assertNotNull(draft)
    assertEquals("คุยงาน", draft.title)
    assertEquals("2026-10-30T07:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse bai song krueng 14_30`() {
    val draft = AppointmentParser.parse("มีนัดสัมภาษณ์งานบ่ายสองครึ่งพรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("สัมภาษณ์งาน", draft.title)
    assertEquals("2026-10-31T07:30:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 4 mong yen 16_00`() {
    val draft = AppointmentParser.parse("คอลกับลูกค้า 4 โมงเย็นวันนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("คอลกับลูกค้า", draft.title)
    assertEquals("2026-10-30T09:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 6 mong yen 18_00`() {
    val draft = AppointmentParser.parse("เจอกันกินเลี้ยง 6 โมงเย็นพรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("เจอกันกินเลี้ยง", draft.title)
    assertEquals("2026-10-31T11:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 1 thum 19_00`() {
    val draft = AppointmentParser.parse("มีนัดกินข้าว 1 ทุ่มวันนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("กินข้าว", draft.title)
    assertEquals("2026-10-30T12:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 2 thum krueng 20_30`() {
    val draft = AppointmentParser.parse("นัดดูหนัง 2 ทุ่มครึ่งพรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("ดูหนัง", draft.title)
    assertEquals("2026-10-31T13:30:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse 5 thum 23_00`() {
    val draft = AppointmentParser.parse("คอลคุยกับทีม 5 ทุ่มวันนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("คอลคุยกับทีม", draft.title)
    assertEquals("2026-10-30T16:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse dtee 2 early morning 02_00`() {
    val draft = AppointmentParser.parse("มีนัดดูบอลตี 2 พรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("ดูบอล", draft.title)
    assertEquals("2026-10-30T19:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse dtee 5 krueng 05_30`() {
    val draft = AppointmentParser.parse("นัดวิ่งตี 5 ครึ่งพรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("วิ่ง", draft.title)
    assertEquals("2026-10-30T22:30:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse thai digits in time and date`() {
    val draft = AppointmentParser.parse("มีนัดตรวจสุขภาพ ๑๐.๐๐น. พรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("ตรวจสุขภาพ", draft.title)
    assertEquals("2026-10-31T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `parse thai digits in bai and date`() {
    val draft = AppointmentParser.parse("นัดพบแพทย์บ่าย ๒ วันที่ ๕", now, TZ)
    assertNotNull(draft)
    assertEquals("พบแพทย์", draft.title)
    assertEquals("2026-11-05T07:00:00.000Z", iso(draft.startUtcMs))
  }

  // --- Date Formats ---

  @Test fun `weekday wan-suk ahead matches today`() {
    val draft = AppointmentParser.parse("นัดประชุมวันศุกร์ 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุม", draft.title)
    assertEquals("2026-10-30T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday wan-suk passed matches next week`() {
    val draft = AppointmentParser.parse("นัดประชุมวันศุกร์ 07:00", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุม", draft.title)
    assertEquals("2026-11-06T00:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday suk-nee matches today when ahead`() {
    val draft = AppointmentParser.parse("มีนัดศุกร์นี้ 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("2026-10-30T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday suk-na matches the week after`() {
    val draft = AppointmentParser.parse("มีนัดศุกร์หน้า 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("2026-11-06T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday wan-chan-na matches next monday plus week`() {
    val draft = AppointmentParser.parse("นัดสัมภาษณ์งานวันจันทร์หน้า 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("สัมภาษณ์งาน", draft.title)
    assertEquals("2026-11-09T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday sao-nee matches tomorrow`() {
    val draft = AppointmentParser.parse("มีนัดเสาร์นี้ 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("2026-10-31T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `weekday athit-nee matches sunday`() {
    val draft = AppointmentParser.parse("มีนัดอาทิตย์นี้ 10:00", now, TZ)
    assertNotNull(draft)
    assertEquals("2026-11-01T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date wan-thi 5 rolls to next month because Oct 5 has passed`() {
    val draft = AppointmentParser.parse("มีนัดฉีดวัคซีน 10:00 วันที่ 5", now, TZ)
    assertNotNull(draft)
    assertEquals("ฉีดวัคซีน", draft.title)
    assertEquals("2026-11-05T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date wan-thi 31 stays this month because Oct 31 has not passed`() {
    val draft = AppointmentParser.parse("มีนัดตรวจตา 10:00 วันที่ 31", now, TZ)
    assertNotNull(draft)
    assertEquals("ตรวจตา", draft.title)
    assertEquals("2026-10-31T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date thai month abbreviated 5 phor-yor`() {
    val draft = AppointmentParser.parse("มีนัดขูดหินปูน 10:00 วันที่ 5 พ.ย.", now, TZ)
    assertNotNull(draft)
    assertEquals("ขูดหินปูน", draft.title)
    assertEquals("2026-11-05T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date thai month full phrutsachikayon`() {
    val draft = AppointmentParser.parse("มีนัดพบหมอ 10:00 5 พฤศจิกายน", now, TZ)
    assertNotNull(draft)
    assertEquals("พบหมอ", draft.title)
    assertEquals("2026-11-05T03:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date slash format 5_11`() {
    val draft = AppointmentParser.parse("นัดกินข้าว 12:00 5/11", now, TZ)
    assertNotNull(draft)
    assertEquals("กินข้าว", draft.title)
    assertEquals("2026-11-05T05:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date slash with BE year 2569`() {
    val draft = AppointmentParser.parse("ประชุม 14:00 5/11/2569", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุม", draft.title)
    assertEquals("2026-11-05T07:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `date slash with CE year 2026`() {
    val draft = AppointmentParser.parse("ประชุม 14:00 5/11/2026", now, TZ)
    assertNotNull(draft)
    assertEquals("ประชุม", draft.title)
    assertEquals("2026-11-05T07:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `no date uses today when time is ahead`() {
    val draft = AppointmentParser.parse("มีนัดคุยงาน 15:00", now, TZ)
    assertNotNull(draft)
    assertEquals("คุยงาน", draft.title)
    assertEquals("2026-10-30T08:00:00.000Z", iso(draft.startUtcMs))
  }

  @Test fun `no date rolls to tomorrow when time has passed`() {
    val draft = AppointmentParser.parse("มีนัดคุยงาน 07:00", now, TZ)
    assertNotNull(draft)
    assertEquals("คุยงาน", draft.title)
    assertEquals("2026-10-31T00:00:00.000Z", iso(draft.startUtcMs))
  }

  // --- Title and Particles ---

  @Test fun `empty title defaults to nat`() {
    val draft = AppointmentParser.parse("มีนัด 10:00 พรุ่งนี้", now, TZ)
    assertNotNull(draft)
    assertEquals("นัด", draft.title)
  }

  @Test fun `particles khrap kha ka na ja are stripped`() {
    assertEquals("ตรวจสุขภาพ", AppointmentParser.parse("มีนัดตรวจสุขภาพ 10:00 พรุ่งนี้ครับ", now, TZ)?.title)
    assertEquals("คุยงาน", AppointmentParser.parse("นัดคุยงาน 14:00 วันนี้ค่ะ", now, TZ)?.title)
    assertEquals("ไปหาหมอ", AppointmentParser.parse("ไปหาหมอ 11:00 พรุ่งนี้นะ", now, TZ)?.title)
    assertEquals("เจอกัน", AppointmentParser.parse("เจอกัน 18:00 วันนี้จ้า", now, TZ)?.title)
    assertEquals("ทานข้าว", AppointmentParser.parse("นัดทานข้าว 12:00 วันนี้ นะคะ", now, TZ)?.title)
  }

  // --- Negatives: Questions ---

  @Test fun `questions return null`() {
    assertNull(AppointmentParser.parse("พรุ่งนี้มีนัดขูดหินปูนกี่โมง", now, TZ))
    assertNull(AppointmentParser.parse("วันศุกร์มีประชุมไหม", now, TZ))
    assertNull(AppointmentParser.parse("มีนัดหมอ 10 โมงมั้ย", now, TZ))
    assertNull(AppointmentParser.parse("มีนัดอะไร 10 โมง", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00 เมื่อไหร่", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00 เมื่อไร", now, TZ))
    assertNull(AppointmentParser.parse("เจอกัน 10:00 ที่ไหน", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00 บ้าง", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00 หรือเปล่า", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00?", now, TZ))
  }

  // --- Negatives: Intention Commands ---

  @Test fun `intention commands return null`() {
    assertNull(AppointmentParser.parse("ถ้ามีนัด 10:00 ให้บันทึก", now, TZ))
    assertNull(AppointmentParser.parse("ทุกครั้งที่มีนัด 10:00 ช่วยบอก", now, TZ))
    assertNull(AppointmentParser.parse("มีนัด 10:00 ให้เตือนด้วย", now, TZ))
  }

  // --- Negatives: No Cue, No Time, Plain Notes ---

  @Test fun `missing cue returns null`() {
    assertNull(AppointmentParser.parse("พรุ่งนี้ 10:00 ไปซื้อของ", now, TZ))
    assertNull(AppointmentParser.parse("ฝนตก 14:00 วันนี้", now, TZ))
  }

  @Test fun `missing clock time returns null`() {
    assertNull(AppointmentParser.parse("พรุ่งนี้มีนัดขูดหินปูน", now, TZ))
    assertNull(AppointmentParser.parse("ประชุมทีมขายวันศุกร์", now, TZ))
    assertNull(AppointmentParser.parse("ไปหาหมอพรุ่งนี้", now, TZ))
    assertNull(AppointmentParser.parse("เจอกันพรุ่งนี้นะ", now, TZ))
  }

  @Test fun `plain notes return null`() {
    assertNull(AppointmentParser.parse("ซื้อไข่ไก่ นม ขนมปัง", now, TZ))
    assertNull(AppointmentParser.parse("สวัสดีครับ วันนี้อากาศดี", now, TZ))
  }
}
