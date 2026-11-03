package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.EndingParticle
import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryFactsTest {
  private val lines = listOf(
    "18:30 มีนัด “ดินเนอร์กับแม่” ถึง 20:00ค่ะ",
    "08:00 ออกจากถนน สายลมเย็นค่ะ",
    "12:08 ถึงถนน ริมคลองใส แห่งที่ 2 อยู่ราว 1 ชั่วโมงค่ะ",
    "08:46 ถึงที่ทำงาน ใช้เวลาเดินทางราว 45 นาที อยู่ราว 3 ชั่วโมงค่ะ",
    "17:59 ถึงบ้านค่ะ",
  )

  @Test fun `diary lines become time-sorted time, place, what facts`() {
    val facts = SummaryFacts.extract(lines, EndingParticle.KA)
    assertEquals(
      listOf(
        "08:00 · ถนน สายลมเย็น · ออกจาก",
        "08:46 · ที่ทำงาน · ถึง ใช้เวลาเดินทางราว 45 นาที อยู่ราว 3 ชั่วโมง",
        "12:08 · ถนน ริมคลองใส แห่งที่ 2 · ถึง อยู่ราว 1 ชั่วโมง",
        "17:59 · บ้าน · ถึง",
        "18:30 · มีนัด “ดินเนอร์กับแม่” ถึง 20:00",
      ),
      facts.map { it.structured },
    )
    assertEquals("08:00 ออกจากถนน สายลมเย็น", facts.first().line) // the checker's fact keeps the diary wording
  }

  @Test fun `facts group into morning, afternoon and evening, empty parts skipped`() {
    val parts = SummaryFacts.sections(SummaryFacts.extract(lines, EndingParticle.KA))
    assertEquals(listOf(SummaryPart.MORNING, SummaryPart.AFTERNOON, SummaryPart.EVENING), parts.map { it.part })
    assertEquals(listOf("12:08", "17:59"), parts[1].facts.map { it.time })
    val noAfternoon = SummaryFacts.sections(SummaryFacts.extract(listOf("11:59 ถึงบ้าน", "18:00 ออกจากบ้าน"), EndingParticle.NONE))
    assertEquals(listOf(SummaryPart.MORNING, SummaryPart.EVENING), noAfternoon.map { it.part })
  }

  @Test fun `a part that fails reads as its own template sentence without a particle`() {
    val part = SummaryFacts.sections(SummaryFacts.extract(lines, EndingParticle.KA))[1]
    assertEquals(
      "ช่วงบ่าย เวลา 12:08 ถึงถนน ริมคลองใส แห่งที่ 2 อยู่ราว 1 ชั่วโมง จากนั้นเวลา 17:59 ถึงบ้าน",
      SummaryTemplate.section(part),
    )
  }
}
