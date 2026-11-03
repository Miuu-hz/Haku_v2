package com.bbpillow.haku.surface.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatParticleTest {
  @Test fun `withParticle swaps particle choosing ka vs kha by body`() {
    assertEquals("ได้เลยนะคะ", ChatPresenter.withParticle("ได้เลยนะค่ะ", "ค่ะ"))
    assertEquals("ว่างไหมคะ", ChatPresenter.withParticle("ว่างไหมค่ะ", "ค่ะ"))
    assertEquals("เรียบร้อยค่ะ", ChatPresenter.withParticle("เรียบร้อยคะ", "ค่ะ"))
    assertEquals("เรียบร้อยค่ะ", ChatPresenter.withParticle("เรียบร้อยครับ", "ค่ะ"))
    assertEquals("ว่างไหมคะ", ChatPresenter.withParticle("ว่างไหมครับ", "ค่ะ"))
  }

  @Test fun `withParticle appends particle when no final particle exists`() {
    assertEquals("ได้เลยนะคะ", ChatPresenter.withParticle("ได้เลยนะ", "ค่ะ"))
    assertEquals("14:30 ค่ะ", ChatPresenter.withParticle("14:30", "ค่ะ"))
    assertEquals("ได้เลยนะครับ", ChatPresenter.withParticle("ได้เลยนะ", "ครับ"))
    assertEquals("14:30 ค่ะ", ChatPresenter.withParticle("14:30ค่ะ", "ค่ะ"))
  }

  @Test fun `withParticle swaps to krap properly`() {
    assertEquals("ได้เลยนะครับ", ChatPresenter.withParticle("ได้เลยนะคะ", "ครับ"))
    assertEquals("ได้เลยนะครับ", ChatPresenter.withParticle("ได้เลยนะค่ะ", "ครับ"))
    assertEquals("เรียบร้อยครับ", ChatPresenter.withParticle("เรียบร้อยค่ะ", "ครับ"))
  }

  @Test fun `withParticle preserves empty particle or blank text`() {
    assertEquals("ได้เลยนะ", ChatPresenter.withParticle("ได้เลยนะ", ""))
    assertEquals("", ChatPresenter.withParticle("", "ค่ะ"))
    assertEquals("   ", ChatPresenter.withParticle("   ", "ค่ะ"))
  }
}
