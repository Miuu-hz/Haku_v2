package com.bbpillow.haku.skill

/** Which of an MCP tool's inputs a 2–4B model is asked to fill. */
object McpToolSlots {
  /** [tooMany] = the tool cannot be enabled. */
  data class Choice(val slots: List<SkillSlot>, val tooMany: Boolean)

  /**
   * A tool within [SkillRecord.MAX_SLOTS] keeps its inputs as they are. Over the limit, a read-only tool can still work
   * from its required inputs plus the first optional ones: leaving an optional filter out only widens the answer. A tool
   * that may change something stays refused, because a left-out option could change what the command does.
   *
   * @param stringSlots the inputs the model can fill, in the server's order.
   * @param inputCount every input the tool declares, of any type.
   * @param requiredCount how many of those are required, of any type.
   */
  fun choose(stringSlots: List<SkillSlot>, inputCount: Int, requiredCount: Int, readOnly: Boolean): Choice {
    val max = SkillRecord.MAX_SLOTS
    if (inputCount <= max && stringSlots.size <= max) return Choice(stringSlots, tooMany = false)
    val required = stringSlots.filter { it.required }
    // a required input the model cannot fill (say a boolean) would fail on the server every time
    if (readOnly && required.size == requiredCount && required.size <= max) {
      return Choice(required + stringSlots.filterNot { it.required }.take(max - required.size), tooMany = false)
    }
    return Choice(stringSlots.take(max), tooMany = true)
  }
}
