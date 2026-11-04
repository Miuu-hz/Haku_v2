package com.bbpillow.haku.platform.agent

import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.action.ActionGate
import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionRequestInput
import com.bbpillow.haku.action.BlockReason
import com.bbpillow.haku.skill.SkillActions
import com.bbpillow.haku.skill.SkillCall
import com.bbpillow.haku.skill.SkillRecord
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * One [SkillRecord] (built-in, imported or an MCP tool) as a LiteRT-LM tool built at runtime from its slots, so skills
 * need no `@Tool` annotation. The model's arguments are untrusted: [SkillActions.prepare] keeps only declared slots,
 * and the gate decides what runs. A card waiting for the user is reported through [onProposal].
 * [recalled] may answer the call from what the user already fetched: no card, no network. The kept text never goes to this
 * model, which holds tools; the caller summarizes it apart.
 */
class SkillTool(
  private val skill: SkillRecord,
  private val gate: ActionGate,
  private val tzOffsetMinutes: Int,
  private val now: () -> Long = System::currentTimeMillis,
  private val recalled: suspend (ActionRequestInput) -> Boolean = { false },
  private val onProposal: (ActionProposalRecord) -> Unit,
) : OpenApiTool {
  /** Tool names are snake_case identifiers for the model; skill slugs use '-'. */
  val toolName = skill.name.replace('-', '_')

  override fun getToolDescriptionJsonString(): String = JSONObject()
    .put("name", toolName)
    .put("description", skill.description)
    .put(
      "parameters",
      JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { skill.slots.forEach { put(it.name, JSONObject().put("type", "string").put("description", it.description)) } })
        .put("required", JSONArray(skill.slots.filter { it.required }.map { it.name })),
    )
    .toString()

  // LiteRT-LM calls this on its worker thread, so blocking on the gate is fine (same as ToolDefinition.runTool)
  override fun execute(paramsJson: String): String {
    val raw = runCatching { JSONObject(paramsJson) }.getOrNull() ?: return "อ่านข้อมูลไม่ได้ ลองใหม่"
    val args = raw.keys().asSequence().associateWith { raw.optString(it) }
    return when (val call = SkillActions.prepare(skill, args, now(), tzOffsetMinutes)) {
      is SkillCall.AskUser -> call.message
      is SkillCall.Propose -> runBlocking {
        if (recalled(call.request)) return@runBlocking RECALLED_TEXT
        when (val d = gate.submit(call.request)) {
          is ActionDecision.AwaitingUser -> ToolDefinition.AWAITING_USER_TEXT.also { onProposal(d.proposal) }
          is ActionDecision.Ran -> d.proposal.message.orEmpty().also { onProposal(d.proposal) }
          is ActionDecision.Blocked -> if (d.reason == BlockReason.STOPPED) "ผู้ใช้หยุดการทำงานทั้งหมดไว้" else "ทำไม่ได้ตอนนี้"
        }
      }
    }
  }

  private companion object {
    const val RECALLED_TEXT = "มีข้อมูลนี้ในความจำแล้ว ไม่ต้องเรียกซ้ำ"
  }
}
