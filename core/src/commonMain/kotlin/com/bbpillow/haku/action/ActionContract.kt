package com.bbpillow.haku.action

import kotlinx.coroutines.flow.StateFlow

/*
 * P4.1 shared contract: the one road from "someone wants something done" to "it was done".
 * Chat rules, notification rules (P5.8), model tools, skills, MCP and haku-host (P7.0) all call [ActionGate.submit];
 * only the gate calls an [ActionHandler]. Owned by Claude: agents implement against it but do not change it.
 */

/** Four levels from the v1 design. The level belongs to the [ActionHandler], never to the caller, so a model cannot downgrade it. */
enum class PermissionLevel {
  /** Runs at once; audit only (read-only lookups). */
  AUTO,

  /** Runs at once; the user gets a notice afterwards. */
  NOTIFY,

  /** Never runs until the user taps confirm on the action card. */
  CONFIRM,

  /** CONFIRM plus a passed biometric prompt before it runs. */
  BIOMETRIC,
}

/** Who asked. Shown on the card and in the audit log. */
enum class ActionOrigin { CHAT_RULE, NOTIFICATION_RULE, SHARED_TEXT, MODEL_TOOL, SKILL, MCP, HOST }

/**
 * Something a caller wants done. Nothing has run yet.
 *
 * @property action stable id, e.g. `calendar.add`; must match a registered [ActionHandler.action].
 * @property arguments handler-specific values (e.g. `title`, `startUtcMs`, `endUtcMs` for `calendar.add`).
 * @property title Thai one-liner for the card, e.g. "นัดขูดหินปูน".
 * @property detail Thai second line, pre-formatted in core, e.g. "ศ 31 ต.ค. · 10:00–11:00".
 * @property warning optional Thai line, e.g. a calendar conflict; null = nothing to warn.
 * @property dedupeKey while a proposal with this key is still PENDING, a new submit returns that same proposal
 *   (a model retrying the call gets one card, never a second run).
 */
data class ActionRequestInput(
  val action: String,
  val arguments: Map<String, String>,
  val title: String,
  val detail: String,
  val origin: ActionOrigin,
  val warning: String? = null,
  val dedupeKey: String = action + arguments.toSortedMap(),
)

/** Superset of the old AppointmentStatus, so the appointment card maps 1:1. */
enum class ActionStatus { PENDING, RUNNING, DONE, DISMISSED, NEEDS_PERMISSION, FAILED, BLOCKED }

/** One card. Lives in RAM only (lost on process death; the notification button then says the offer expired). */
data class ActionProposalRecord(
  val id: String,
  val request: ActionRequestInput,
  val level: PermissionLevel,
  val status: ActionStatus,
  val createdUtcMs: Long,
  /** Thai result or error after RUNNING, e.g. "เพิ่มลงปฏิทินแล้ว"; null before. */
  val message: String? = null,
)

/** What a handler reports. [needsPermission] = an OS permission is missing; the UI asks for it and calls confirm again. */
data class ActionResultOutput(val ok: Boolean, val message: String, val needsPermission: Boolean = false)

sealed interface ActionDecision {
  /** AUTO / NOTIFY: already ran. */
  data class Ran(val proposal: ActionProposalRecord) : ActionDecision

  /** CONFIRM / BIOMETRIC: a card is waiting. A model tool returns "รอผู้ใช้ยืนยัน" to the model at this point. */
  data class AwaitingUser(val proposal: ActionProposalRecord) : ActionDecision

  data class Blocked(val reason: BlockReason) : ActionDecision
}

enum class BlockReason { UNKNOWN_ACTION, RATE_LIMITED, STOPPED }

/** The code that actually does an action. Registered once per [action]; only [ActionGate] calls [run]. */
interface ActionHandler {
  val action: String
  val level: PermissionLevel
  suspend fun run(arguments: Map<String, String>): ActionResultOutput
}

/**
 * One line per submit and per status change. Stores action, origin, level, status, time and the card [title];
 * never the raw [ActionRequestInput.arguments] (PDPA: keep only what the audit screen shows).
 */
data class AuditRecord(
  val proposalId: String,
  val action: String,
  val origin: ActionOrigin,
  val level: PermissionLevel,
  val status: ActionStatus,
  val title: String,
  val atUtcMs: Long,
)

/** SQL lives in `AuditStore` (same DB as capture; not thread-safe, callers serialize like ConnectionStore). [clear] is called by ForgetEverything. */
interface AuditRepository {
  fun append(record: AuditRecord)
  fun recent(limit: Int): List<AuditRecord>
  fun clear()
}

interface ActionGate {
  /** Cards to show (PENDING, RUNNING, and finished ones until dismissed). */
  val proposals: StateFlow<List<ActionProposalRecord>>

  suspend fun submit(request: ActionRequestInput): ActionDecision

  /**
   * Runs a PENDING (or NEEDS_PERMISSION / FAILED retry) proposal. BIOMETRIC level runs only when [biometricPassed].
   * Unknown or already finished id = returned unchanged, nothing runs.
   */
  suspend fun confirm(proposalId: String, biometricPassed: Boolean = false): ActionProposalRecord?

  fun dismiss(proposalId: String)

  /** Emergency stop: every PENDING card becomes DISMISSED and every submit is Blocked(STOPPED) until [resume]. */
  fun stopAll()

  fun resume()
}
