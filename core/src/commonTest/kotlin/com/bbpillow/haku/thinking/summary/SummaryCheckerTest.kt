package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryCheckerTest {
  private val facts = listOf(
    "08:30 ออกจากบ้านครับ",
    "09:10 ถึงที่ทำงาน ใช้เวลาเดินทางราว 40 นาที อยู่ราว 2 ชั่วโมงครึ่งครับ",
    "12:00 ถึงร้านข้าวลุงสม อยู่ราว 45 นาทีครับ",
    "18:00 มีนัด “ประชุม Sprint”ครับ",
  )
  private val krap = DiaryStyle(EndingParticle.KRAP)
  private fun check(text: String, style: DiaryStyle = krap) = SummaryChecker.check(SummaryCheckInput(text, facts, style)).reasons

  private val ok = "เช้านี้ออกจากบ้านตอน 08:30 แล้วถึงที่ทำงานตอน 09:10 ตอนเที่ยงไปถึงร้านข้าวลุงสมครับ"

  @Test fun `faithful text passes`() = assertEquals(emptyList(), check(ok))

  @Test fun `thai digits and dotted times of a known time pass`() =
    assertEquals(emptyList(), check("เช้านี้ออกจากบ้านตอน ๐๘.๓๐ แล้วถึงที่ทำงานตอน 09:10 ตอนเที่ยงไปถึงร้านข้าวลุงสมครับ"))

  @Test fun `time not in facts is rejected even when its digits appear elsewhere`() =
    assertTrue("invented-time" in check(ok.replace("09:10", "09:30")))

  @Test fun `unknown number and spoken number are rejected`() {
    assertTrue("invented-number" in check(ok.replace("แล้วถึง", "ใช้เวลา 15 นาที แล้วถึง")))
    assertTrue("invented-number" in check(ok.replace("แล้วถึง", "ใช้เวลาสามชั่วโมง แล้วถึง")))
  }

  @Test fun `unknown place after a cue and invented place noun are rejected`() {
    assertTrue("invented-place" in check(ok.replace("ถึงที่ทำงาน", "ถึงโรงพยาบาล")))
    assertTrue("invented-place" in check(ok.replace("ร้านข้าวลุงสม", "สวนลุมพินี")))
  }

  @Test fun `place noun is fine when the facts have it`() =
    assertEquals(emptyList(), check("เช้านี้ออกจากบ้านตอน 08:30 แล้วไปที่ทำงาน ตอนเที่ยงถึงร้านข้าวลุงสมครับ"))

  @Test fun `quoted title must come from facts and latin only from facts`() {
    assertEquals(emptyList(), check(ok.replace("ครับ", " แล้วเย็นมีนัด “ประชุม Sprint” ครับ")))
    assertTrue("invented-quote" in check(ok.replace("ครับ", " เย็นมี “งานเลี้ยง” ครับ")))
    assertTrue("latin-word" in check(ok.replace("ตอนเที่ยง", "ตอน lunch")))
  }

  @Test fun `particle rules for each style`() {
    assertTrue("missing-particle" in check(ok.removeSuffix("ครับ") + "แล้ว"))
    assertTrue("wrong-particle" in check(ok + "ขอบคุณค่ะครับ"))
    val plain = facts.map { it.removeSuffix("ครับ") }
    fun plainCheck(t: String) = SummaryChecker.check(SummaryCheckInput(t, plain, DiaryStyle())).reasons
    assertTrue("unexpected-particle" in plainCheck(ok))
    assertEquals(emptyList(), plainCheck(ok.removeSuffix("ครับ") + "แล้ว"))
    assertEquals(emptyList(), check(ok.removeSuffix("ครับ") + "ค่ะ", DiaryStyle(EndingParticle.KA)))
  }

  @Test fun `length, script and hidden characters`() {
    assertTrue("too-short" in check("สั้นมากครับ"))
    assertTrue("too-long" in check(ok + "ก".repeat(SummaryChecker.MAX_CHARS)))
    assertTrue("not-thai" in check("Left home at the usual time and reached the office on schedule, then had lunch ครับ"))
    assertTrue("control-chars" in check(ok.replace("เช้านี้", "เช้า​นี้")))
  }
}
