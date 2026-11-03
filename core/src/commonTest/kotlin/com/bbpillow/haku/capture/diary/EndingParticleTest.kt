package com.bbpillow.haku.capture.diary

import kotlin.test.Test
import kotlin.test.assertEquals

class EndingParticleTest {
  @Test fun `ka formFor chooses between kha and ka based on body`() {
    val ka = EndingParticle.KA
    // statements -> ค่ะ
    assertEquals("ค่ะ", ka.formFor("ไปตลาดมา"))
    assertEquals("ค่ะ", ka.formFor("14:30"))
    assertEquals("ค่ะ", ka.formFor("เรียบร้อย"))

    // questions -> คะ
    assertEquals("คะ", ka.formFor("พรุ่งนี้ว่างไหม"))
    assertEquals("คะ", ka.formFor("พรุ่งนี้ว่างมั้ย"))
    assertEquals("คะ", ka.formFor("ไปไหนมา"))
    assertEquals("คะ", ka.formFor("ใช่หรือเปล่า"))
    assertEquals("คะ", ka.formFor("กินหรือยังหรือ"))
    assertEquals("คะ", ka.formFor("ไปไหน"))
    assertEquals("คะ", ka.formFor("กินอะไร"))
    assertEquals("คะ", ka.formFor("นัดใคร"))
    assertEquals("คะ", ka.formFor("เริ่มเมื่อไหร่"))
    assertEquals("คะ", ka.formFor("เริ่มเมื่อไร"))
    assertEquals("คะ", ka.formFor("ทำยังไง"))
    assertEquals("คะ", ka.formFor("ทำอย่างไร"))
    assertEquals("คะ", ka.formFor("ทำไม"))
    assertEquals("คะ", ka.formFor("มีกี่"))
    assertEquals("คะ", ka.formFor("มีอะไรบ้าง"))
    assertEquals("คะ", ka.formFor("พร้อมใช่ไหม"))
    assertEquals("คะ", ka.formFor("กินข้าวหรือยัง"))
    assertEquals("คะ", ka.formFor("ไม่ไปไหม"))
    // a question word after ไม่ is an indefinite: statement
    assertEquals("ค่ะ", ka.formFor("วันนี้ไม่ได้ไปไหน"))
    assertEquals("ค่ะ", ka.formFor("ไม่มีอะไร"))
    assertEquals("ค่ะ", ka.formFor("ยังไม่มีใคร"))
    assertEquals("ค่ะ", ka.formFor("หมอนัดอีกทีปีหน้า"))
    assertEquals("ค่ะ", ka.formFor("ทีมเราชนะ"))

    // question ending with ?
    assertEquals("คะ", ka.formFor("พรุ่งนี้ว่างไหม?"))
    assertEquals("คะ", ka.formFor("พร้อมแล้ว?"))
    assertEquals("คะ", ka.formFor("พร้อมแล้ว？"))
    assertEquals("คะ", ka.formFor("พร้อมแล้ว? "))

    // softeners -> คะ
    assertEquals("คะ", ka.formFor("ได้เลยนะ"))
    assertEquals("คะ", ka.formFor("รอก่อนน้า"))
    assertEquals("คะ", ka.formFor("ได้เลยนะ\u200B"))
    assertEquals("คะ", ka.formFor("ขอบคุณจ้ะ"))
    assertEquals("คะ", ka.formFor("เนอะ"))
    assertEquals("คะ", ka.formFor("จริงสิ"))
    assertEquals("คะ", ka.formFor("ทำไมล่ะ"))
  }

  @Test fun `ka formFor normalises existing ending particle`() {
    val ka = EndingParticle.KA
    assertEquals("ค่ะ", ka.formFor("เรียบร้อยคะ"))
    assertEquals("ค่ะ", ka.formFor("เรียบร้อยค่ะ"))
    assertEquals("คะ", ka.formFor("ได้เลยนะค่ะ"))
    assertEquals("คะ", ka.formFor("ได้เลยนะคะ"))
    assertEquals("คะ", ka.formFor("ว่างไหมค่ะ"))
    assertEquals("คะ", ka.formFor("ว่างไหมคะ"))
  }

  @Test fun `ka appendTo joins thai char and spaces digits or latin`() {
    val ka = EndingParticle.KA
    assertEquals("ไปตลาดมาค่ะ", ka.appendTo("ไปตลาดมา"))
    assertEquals("14:30 ค่ะ", ka.appendTo("14:30"))
    assertEquals("meeting ค่ะ", ka.appendTo("meeting"))
    assertEquals("พรุ่งนี้ว่างไหมคะ", ka.appendTo("พรุ่งนี้ว่างไหม"))
    assertEquals("ได้เลยนะคะ", ka.appendTo("ได้เลยนะ"))
    assertEquals("พรุ่งนี้ว่างไหม? คะ", ka.appendTo("พรุ่งนี้ว่างไหม?"))
  }

  @Test fun `ka appendTo normalises existing particle without duplicating`() {
    val ka = EndingParticle.KA
    assertEquals("ไปตลาดมาค่ะ", ka.appendTo("ไปตลาดมาค่ะ"))
    assertEquals("14:30 ค่ะ", ka.appendTo("14:30 ค่ะ"))
    assertEquals("14:30 ค่ะ", ka.appendTo("14:30คะ"))
    assertEquals("เรียบร้อยค่ะ", ka.appendTo("เรียบร้อยคะ"))
    assertEquals("ได้เลยนะคะ", ka.appendTo("ได้เลยนะค่ะ"))
    assertEquals("ว่างไหมคะ", ka.appendTo("ว่างไหมค่ะ"))
  }

  @Test fun `krap always gives krap and preserves spacing rules`() {
    val krap = EndingParticle.KRAP
    assertEquals("ครับ", krap.formFor("ไปตลาดมา"))
    assertEquals("ครับ", krap.formFor("พรุ่งนี้ว่างไหม"))
    assertEquals("ครับ", krap.formFor("ได้เลยนะ"))
    assertEquals("ไปตลาดมาครับ", krap.appendTo("ไปตลาดมา"))
    assertEquals("14:30 ครับ", krap.appendTo("14:30"))
    assertEquals("พรุ่งนี้ว่างไหมครับ", krap.appendTo("พรุ่งนี้ว่างไหม"))
    assertEquals("ได้เลยนะครับ", krap.appendTo("ได้เลยนะ"))
    assertEquals("ไปตลาดมาครับ", krap.appendTo("ไปตลาดมาครับ"))
    assertEquals("14:30 ครับ", krap.appendTo("14:30 ครับ"))
  }

  @Test fun `none never adds anything and formFor is empty`() {
    val none = EndingParticle.NONE
    assertEquals("", none.formFor("ไปตลาดมา"))
    assertEquals("", none.formFor("พรุ่งนี้ว่างไหม"))
    assertEquals("ไปตลาดมา", none.appendTo("ไปตลาดมา"))
    assertEquals("14:30", none.appendTo("14:30"))
    assertEquals("พรุ่งนี้ว่างไหม", none.appendTo("พรุ่งนี้ว่างไหม"))
  }

  @Test fun `stripFrom strips particle suffixes properly`() {
    assertEquals("ไปตลาดมา", EndingParticle.KA.stripFrom("ไปตลาดมาค่ะ"))
    assertEquals("ได้เลยนะ", EndingParticle.KA.stripFrom("ได้เลยนะคะ"))
    assertEquals("14:30 ", EndingParticle.KA.stripFrom("14:30 ค่ะ"))
    assertEquals("ไปตลาดมา", EndingParticle.KRAP.stripFrom("ไปตลาดมาครับ"))
    assertEquals("14:30 ", EndingParticle.KRAP.stripFrom("14:30 ครับ"))
    assertEquals("ไปตลาดมา", EndingParticle.NONE.stripFrom("ไปตลาดมา"))
  }
}
