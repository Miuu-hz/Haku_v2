package com.bbpillow.haku.action

import com.bbpillow.haku.surface.chat.AppointmentProposal
import com.bbpillow.haku.surface.chat.AppointmentStatus

/** Validated arguments for the `calendar.add` action. */
data class CalendarAddInput(
  val title: String,
  val startUtcMs: Long,
  val endUtcMs: Long,
  val allDay: Boolean = false,
)

/**
 * Converts an [AppointmentProposal] (chat or notification rule) into a `calendar.add` [ActionRequestInput] and back.
 * Returns null on malformed or missing arguments.
 */
object CalendarAddArgs {
  const val ACTION = "calendar.add"
  const val KEY_TITLE = "title"
  const val KEY_START_UTC_MS = "startUtcMs"
  const val KEY_END_UTC_MS = "endUtcMs"
  const val KEY_ALL_DAY = "allDay"
  private const val DETAIL_SEP = " · "

  fun toRequest(
    proposal: AppointmentProposal,
    origin: ActionOrigin = ActionOrigin.CHAT_RULE,
  ): ActionRequestInput = ActionRequestInput(
    action = ACTION,
    arguments = buildMap {
      put(KEY_TITLE, proposal.title)
      put(KEY_START_UTC_MS, proposal.startUtcMs.toString())
      put(KEY_END_UTC_MS, proposal.endUtcMs.toString())
      if (proposal.allDay) {
        put(KEY_ALL_DAY, "true")
      }
    },
    title = proposal.title,
    detail = "${proposal.dayLabel}$DETAIL_SEP${proposal.timeLabel}",
    origin = origin,
    warning = proposal.conflict,
  )

  fun fromProposal(
    proposal: AppointmentProposal,
    origin: ActionOrigin = ActionOrigin.CHAT_RULE,
  ): ActionRequestInput = toRequest(proposal, origin)

  fun parse(arguments: Map<String, String>): CalendarAddInput? {
    val title = arguments[KEY_TITLE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val startUtcMs = arguments[KEY_START_UTC_MS]?.toLongOrNull() ?: return null
    val endUtcMs = arguments[KEY_END_UTC_MS]?.toLongOrNull() ?: return null
    if (endUtcMs < startUtcMs) return null
    val allDay = arguments[KEY_ALL_DAY]?.toBoolean() ?: false
    return CalendarAddInput(title = title, startUtcMs = startUtcMs, endUtcMs = endUtcMs, allDay = allDay)
  }

  fun toProposal(
    request: ActionRequestInput,
    status: AppointmentStatus = AppointmentStatus.PENDING,
  ): AppointmentProposal? {
    if (request.action != ACTION) return null
    val parsed = parse(request.arguments) ?: return null
    val parts = request.detail.split(DETAIL_SEP, limit = 2)
    val dayLabel = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val timeLabel = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return AppointmentProposal(
      title = parsed.title,
      startUtcMs = parsed.startUtcMs,
      endUtcMs = parsed.endUtcMs,
      dayLabel = dayLabel,
      timeLabel = timeLabel,
      conflict = request.warning,
      status = status,
      allDay = parsed.allDay,
    )
  }

  fun toProposal(record: ActionProposalRecord): AppointmentProposal? =
    toProposal(record.request, record.status.toAppointmentStatus())

  fun ActionStatus.toAppointmentStatus(): AppointmentStatus = when (this) {
    ActionStatus.PENDING -> AppointmentStatus.PENDING
    ActionStatus.RUNNING -> AppointmentStatus.SAVING
    ActionStatus.DONE -> AppointmentStatus.ADDED
    ActionStatus.DISMISSED -> AppointmentStatus.DISMISSED
    ActionStatus.NEEDS_PERMISSION -> AppointmentStatus.NEEDS_PERMISSION
    ActionStatus.FAILED, ActionStatus.BLOCKED -> AppointmentStatus.FAILED
  }
}
