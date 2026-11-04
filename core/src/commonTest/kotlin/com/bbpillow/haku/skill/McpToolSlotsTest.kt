package com.bbpillow.haku.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolSlotsTest {
  private fun slot(name: String, required: Boolean = false) = SkillSlot(name, name, required)

  // the ERP's search: query required, category and stock_type optional strings, low_stock an optional boolean
  private val searchSlots = listOf(slot("query", required = true), slot("category"), slot("stock_type"))

  @Test fun readOnlyToolOverTheLimitKeepsRequiredThenFirstOptional() {
    val choice = McpToolSlots.choose(searchSlots, inputCount = 4, requiredCount = 1, readOnly = true)
    assertFalse(choice.tooMany)
    assertEquals(listOf("query", "category", "stock_type"), choice.slots.map { it.name })
  }

  @Test fun requiredInputsComeFirstWhenOptionalOnesAreLeftOut() {
    val slots = listOf(slot("a"), slot("b"), slot("c"), slot("d", required = true))
    val choice = McpToolSlots.choose(slots, inputCount = 4, requiredCount = 1, readOnly = true)
    assertFalse(choice.tooMany)
    assertEquals(listOf("d", "a", "b"), choice.slots.map { it.name })
  }

  @Test fun toolThatMayChangeSomethingStaysRefusedOverTheLimit() {
    val choice = McpToolSlots.choose(searchSlots, inputCount = 4, requiredCount = 1, readOnly = false)
    assertTrue(choice.tooMany)
  }

  @Test fun readOnlyToolWithTooManyRequiredInputsIsRefused() {
    val slots = (1..4).map { slot("r$it", required = true) }
    assertTrue(McpToolSlots.choose(slots, inputCount = 4, requiredCount = 4, readOnly = true).tooMany)
  }

  @Test fun readOnlyToolWithARequiredInputTheModelCannotFillIsRefused() {
    val slots = listOf(slot("a"), slot("b"), slot("c"))
    assertTrue(McpToolSlots.choose(slots, inputCount = 4, requiredCount = 1, readOnly = true).tooMany)
  }

  @Test fun toolWithinTheLimitIsLeftAlone() {
    val slots = listOf(slot("a"), slot("b", required = true))
    val choice = McpToolSlots.choose(slots, inputCount = 2, requiredCount = 1, readOnly = false)
    assertFalse(choice.tooMany)
    assertEquals(slots, choice.slots)
  }
}
