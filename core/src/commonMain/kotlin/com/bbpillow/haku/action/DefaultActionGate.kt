package com.bbpillow.haku.action

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P4.1 single action gate. Every caller submits an [ActionRequestInput]; only this gate invokes [ActionHandler.run].
 *
 * [PermissionLevel.CONFIRM] and [PermissionLevel.BIOMETRIC] never run on [submit]—they wait as a [ActionStatus.PENDING]
 * proposal until the user triggers [confirm]. Every submit and status change is recorded in [audit] under [mutex].
 */
class DefaultActionGate(
  handlers: List<ActionHandler>,
  private val audit: AuditRepository,
  private val clock: () -> Long,
  private val newId: () -> String,
  private val onAudit: (AuditRecord) -> Unit = audit::append,
) : ActionGate {
  private val handlersByAction = handlers.associateBy { it.action }
  private val _proposals = MutableStateFlow<List<ActionProposalRecord>>(emptyList())
  override val proposals: StateFlow<List<ActionProposalRecord>> = _proposals.asStateFlow()

  private val _stopped = MutableStateFlow(false)
  val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

  // ponytail: single gate mutex serializes state transitions, rate-limit history, and non-thread-safe audit writes.
  // Never held across handler.run(), so non-suspend dismiss/stopAll/resume can acquire it without suspension.
  private val mutex = Mutex()
  private val submitHistory = mutableMapOf<String, MutableList<Long>>()

  override suspend fun submit(request: ActionRequestInput): ActionDecision {
    val autoProposal: ActionProposalRecord
    val handler: ActionHandler
    mutex.withLock {
      val now = clock()
      val found = handlersByAction[request.action]
      if (found == null) {
        recordAudit(newId(), request, PermissionLevel.CONFIRM, ActionStatus.BLOCKED, now)
        return ActionDecision.Blocked(BlockReason.UNKNOWN_ACTION)
      }
      handler = found
      if (_stopped.value) {
        recordAudit(newId(), request, handler.level, ActionStatus.BLOCKED, now)
        return ActionDecision.Blocked(BlockReason.STOPPED)
      }
      val history = submitHistory.getOrPut(request.action) { mutableListOf() }
      history.removeAll { now - it >= RATE_LIMIT_WINDOW_MS }
      if (history.size >= MAX_SUBMITS_PER_WINDOW) {
        recordAudit(newId(), request, handler.level, ActionStatus.BLOCKED, now)
        return ActionDecision.Blocked(BlockReason.RATE_LIMITED)
      }
      history.add(now)

      val existing = _proposals.value.firstOrNull {
        it.status == ActionStatus.PENDING && it.request.dedupeKey == request.dedupeKey
      }
      if (existing != null) {
        recordAudit(existing.id, request, existing.level, ActionStatus.PENDING, now)
        return ActionDecision.AwaitingUser(existing)
      }

      when (handler.level) {
        PermissionLevel.CONFIRM, PermissionLevel.BIOMETRIC -> {
          val pending = ActionProposalRecord(
            id = newId(),
            request = request,
            level = handler.level,
            status = ActionStatus.PENDING,
            createdUtcMs = now,
          )
          _proposals.update { it + pending }
          recordAudit(pending, now)
          return ActionDecision.AwaitingUser(pending)
        }
        PermissionLevel.AUTO, PermissionLevel.NOTIFY -> {
          autoProposal = ActionProposalRecord(
            id = newId(),
            request = request,
            level = handler.level,
            status = ActionStatus.RUNNING,
            createdUtcMs = now,
          )
        }
      }
    }

    val outcome = runHandler(handler, request.arguments)
    return mutex.withLock {
      val finished = autoProposal.copy(status = statusFromResult(outcome), message = outcome.message)
      recordAudit(finished, clock())
      ActionDecision.Ran(finished)
    }
  }

  override suspend fun confirm(proposalId: String, biometricPassed: Boolean): ActionProposalRecord? {
    val running: ActionProposalRecord
    val handler: ActionHandler
    mutex.withLock {
      val current = _proposals.value.firstOrNull { it.id == proposalId } ?: return null
      if (_stopped.value) return current
      if (current.status !in RUNNABLE_STATUSES) return current
      if (current.level == PermissionLevel.BIOMETRIC && !biometricPassed) return current
      handler = handlersByAction[current.request.action] ?: return current

      running = current.copy(status = ActionStatus.RUNNING, message = null)
      _proposals.update { list -> list.map { if (it.id == proposalId) running else it } }
      recordAudit(running, clock())
    }

    val outcome = runHandler(handler, running.request.arguments)
    return mutex.withLock {
      val finished = running.copy(status = statusFromResult(outcome), message = outcome.message)
      _proposals.update { list -> list.map { if (it.id == proposalId) finished else it } }
      recordAudit(finished, clock())
      finished
    }
  }

  override fun dismiss(proposalId: String) = withSyncLock {
    val current = _proposals.value.firstOrNull { it.id == proposalId } ?: return@withSyncLock
    if (current.status == ActionStatus.DISMISSED) return@withSyncLock
    val dismissed = current.copy(status = ActionStatus.DISMISSED)
    _proposals.update { list -> list.map { if (it.id == proposalId) dismissed else it } }
    recordAudit(dismissed, clock())
  }

  override fun stopAll() = withSyncLock {
    _stopped.value = true
    val now = clock()
    val dismissed = mutableListOf<ActionProposalRecord>()
    _proposals.update { list ->
      list.map { p ->
        if (p.status == ActionStatus.PENDING) {
          p.copy(status = ActionStatus.DISMISSED).also { dismissed += it }
        } else {
          p
        }
      }
    }
    dismissed.forEach { recordAudit(it, now) }
  }

  override fun resume() = withSyncLock {
    _stopped.value = false
  }

  private suspend fun runHandler(handler: ActionHandler, arguments: Map<String, String>): ActionResultOutput =
    try {
      handler.run(arguments)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      ActionResultOutput(ok = false, message = HANDLER_ERROR_MESSAGE)
    }

  private fun statusFromResult(result: ActionResultOutput): ActionStatus = when {
    result.needsPermission -> ActionStatus.NEEDS_PERMISSION
    result.ok -> ActionStatus.DONE
    else -> ActionStatus.FAILED
  }

  private fun recordAudit(proposal: ActionProposalRecord, atUtcMs: Long) {
    recordAudit(proposal.id, proposal.request, proposal.level, proposal.status, atUtcMs)
  }

  private fun recordAudit(
    proposalId: String,
    request: ActionRequestInput,
    level: PermissionLevel,
    status: ActionStatus,
    atUtcMs: Long,
  ) {
    onAudit(
      AuditRecord(
        proposalId = proposalId,
        action = request.action,
        origin = request.origin,
        level = level,
        status = status,
        title = request.title,
        atUtcMs = atUtcMs,
      ),
    )
  }

  private inline fun <T> withSyncLock(block: () -> T): T {
    while (!mutex.tryLock()) {
      // mutex is only held for in-memory updates + synchronous audit append, never across suspension points
    }
    return try {
      block()
    } finally {
      mutex.unlock()
    }
  }

  companion object {
    const val MAX_SUBMITS_PER_WINDOW = 5
    const val RATE_LIMIT_WINDOW_MS = 60_000L
    const val HANDLER_ERROR_MESSAGE = "ทำรายการไม่สำเร็จ"
    private val RUNNABLE_STATUSES = setOf(ActionStatus.PENDING, ActionStatus.NEEDS_PERMISSION, ActionStatus.FAILED)
  }
}
