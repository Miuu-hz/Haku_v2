package com.bbpillow.haku.skill

import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.ActionRequestInput
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.surface.chat.AppointmentCommand

/** What a skill call turns into: a card for the gate, or a Thai line back to the model so it asks the user. */
sealed interface SkillCall {
  data class Propose(val request: ActionRequestInput) : SkillCall
  data class AskUser(val message: String) : SkillCall
}

/**
 * Validated slots → [ActionRequestInput]. The card must show what will happen (date and time for an appointment,
 * the exact values and the destination host for an MCP call) before the user taps confirm.
 */
object SkillActions {
  const val MCP_ACTION = "mcp.call"
  const val KEY_SERVER = "_server"
  const val KEY_TOOL = "_tool"

  fun prepare(skill: SkillRecord, rawArguments: Map<String, String>, nowUtcMs: Long, tzOffsetMinutes: Int): SkillCall {
    val args = when (val r = skill.validate(rawArguments)) {
      is SlotResult.Missing -> return SkillCall.AskUser("ยังขาด: " + r.slots.joinToString { n -> skill.slots.first { it.name == n }.description })
      is SlotResult.Ok -> r.arguments
    }
    return when {
      skill.action == CalendarAddArgs.ACTION -> calendar(args, nowUtcMs, tzOffsetMinutes)
      skill.source == SkillSource.MCP -> SkillCall.Propose(
        ActionRequestInput(
          action = MCP_ACTION,
          arguments = args + (KEY_SERVER to skill.server.orEmpty()) + (KEY_TOOL to skill.name),
          title = "${skill.title} · ส่งไปที่ ${skill.server}",
          detail = args.entries.joinToString(" · ") { "${it.key}: ${it.value}" },
          origin = ActionOrigin.MCP,
          warning = "ข้อมูลนี้จะออกจากเครื่องไปที่ ${skill.server}",
        ),
      )
      else -> SkillCall.Propose(
        ActionRequestInput(skill.action, args, skill.title, args.values.joinToString(" · "), ActionOrigin.SKILL),
      )
    }
  }

  /** `title` + free-text `when` → the same parser and card labels as typed appointments (P5.4). */
  private fun calendar(args: Map<String, String>, nowUtcMs: Long, tz: Int): SkillCall {
    val title = args.getValue("title")
    // the parser needs a cue word ("นัด") to treat the text as an appointment
    val proposal = AppointmentCommand().tryPropose("นัด$title ${args["when"].orEmpty()}", nowUtcMs, tz)?.appointment
      ?: return SkillCall.AskUser("ยังไม่รู้วันและเวลาของนัด ถามผู้ใช้ก่อน")
    return SkillCall.Propose(CalendarAddArgs.toRequest(proposal.copy(title = title), ActionOrigin.SKILL))
  }
}

/** Skills that ship with the app. Each maps to a handler already registered on the gate. */
object BuiltInSkills {
  val ADD_APPOINTMENT = SkillFormat.parse(
    """
    ---
    name: add-appointment
    description: Add one appointment or meeting to the phone calendar
    title: เพิ่มนัด
    aliases: นัด, ปฏิทิน, ลงนัด
    action: calendar.add
    slots: title=ชื่อนัด สั้นๆ; when=วันและเวลา ตามที่ผู้ใช้พูด เช่น พรุ่งนี้ 10 โมง
    ---
    เรียก tool ครั้งเดียว ใส่ title เป็นชื่อนัดสั้นๆ และ when เป็นวันเวลาตามคำของผู้ใช้ ห้ามเดาวันเวลาเอง ถ้าผู้ใช้ไม่ได้บอกเวลา ให้ถามก่อน
    """.trimIndent(),
    SkillSource.BUILT_IN,
  )

  val ALL = listOf(ADD_APPOINTMENT)
}
