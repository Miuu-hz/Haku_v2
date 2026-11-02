package com.bbpillow.haku.action

import com.bbpillow.haku.surface.chat.AppointmentProposal
import com.bbpillow.haku.surface.chat.AppointmentStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

private class FakeAuditRepository : AuditRepository {
  val records = mutableListOf<AuditRecord>()

  override fun append(record: AuditRecord) {
    records += record
  }

  override fun recent(limit: Int): List<AuditRecord> = records.asReversed().take(limit)

  override fun clear() {
    records.clear()
  }
}

private class FakeActionHandler(
  override val action: String,
  override val level: PermissionLevel,
  private val onRun: suspend (Map<String, String>) -> ActionResultOutput = {
    ActionResultOutput(ok = true, message = "สำเร็จ")
  },
) : ActionHandler {
  var runs = 0
  val receivedArgs = mutableListOf<Map<String, String>>()

  override suspend fun run(arguments: Map<String, String>): ActionResultOutput {
    runs++
    receivedArgs += arguments
    return onRun(arguments)
  }
}

class DefaultActionGateTest {
  private var now = 1_790_000_000_000L
  private var nextId = 1
  private val audit = FakeAuditRepository()

  private fun gate(vararg handlers: ActionHandler) =
    DefaultActionGate(handlers.toList(), audit::append, clock = { now }, newId = { "p-${nextId++}" })

  private fun req(
    action: String = CalendarAddArgs.ACTION,
    args: Map<String, String> = mapOf("title" to "นัดหมอฟัน", "startUtcMs" to "100", "endUtcMs" to "200"),
    title: String = "นัดหมอฟัน",
    detail: String = "ศ 31 ต.ค. · 10:00–11:00",
    origin: ActionOrigin = ActionOrigin.MODEL_TOOL,
    warning: String? = null,
  ) = ActionRequestInput(action, args, title, detail, origin, warning)

  @Test
  fun passCriterionHundredRepeatedSubmitsProduceOneCardAndZeroRuns() = runTest {
    val handler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM)
    val g = gate(handler)

    repeat(100) {
      g.submit(req())
      now += 20_000L // advance so both dedupe and rate-limit windows are exercised across 100 calls
    }

    assertEquals(1, g.proposals.value.size)
    assertEquals(ActionStatus.PENDING, g.proposals.value.single().status)
    assertEquals(0, handler.runs)

    val confirmed = g.confirm(g.proposals.value.single().id)
    assertNotNull(confirmed)
    assertEquals(ActionStatus.DONE, confirmed.status)
    assertEquals(1, handler.runs)
  }

  @Test
  fun dedupeReturnsExistingPendingProposalAndCreatesNewCardAfterDismiss() = runTest {
    val handler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM)
    val g = gate(handler)

    val first = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))
    val second = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))
    assertEquals(first.proposal.id, second.proposal.id)
    assertEquals(1, g.proposals.value.size)
    assertEquals(0, handler.runs)

    g.dismiss(first.proposal.id)
    assertEquals(ActionStatus.DISMISSED, g.proposals.value.single().status)

    val third = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))
    assertEquals("p-2", third.proposal.id)
    assertEquals(2, g.proposals.value.size)
  }

  @Test
  fun eachPermissionLevelBehavesAsSpecified() = runTest {
    val autoHandler = FakeActionHandler("lookup.time", PermissionLevel.AUTO)
    val notifyHandler = FakeActionHandler("note.quick", PermissionLevel.NOTIFY)
    val confirmHandler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM)
    val bioHandler = FakeActionHandler("memory.export", PermissionLevel.BIOMETRIC)
    val g = gate(autoHandler, notifyHandler, confirmHandler, bioHandler)

    val autoDec = assertIs<ActionDecision.Ran>(g.submit(req(action = "lookup.time")))
    assertEquals(ActionStatus.DONE, autoDec.proposal.status)
    assertEquals(1, autoHandler.runs)
    assertTrue(g.proposals.value.isEmpty())

    val notifyDec = assertIs<ActionDecision.Ran>(g.submit(req(action = "note.quick")))
    assertEquals(ActionStatus.DONE, notifyDec.proposal.status)
    assertEquals(1, notifyHandler.runs)
    assertTrue(g.proposals.value.isEmpty())

    val confirmDec = assertIs<ActionDecision.AwaitingUser>(g.submit(req(action = CalendarAddArgs.ACTION)))
    assertEquals(ActionStatus.PENDING, confirmDec.proposal.status)
    assertEquals(0, confirmHandler.runs)

    val bioDec = assertIs<ActionDecision.AwaitingUser>(g.submit(req(action = "memory.export")))
    assertEquals(ActionStatus.PENDING, bioDec.proposal.status)
    assertEquals(0, bioHandler.runs)
  }

  @Test
  fun biometricRequiresBiometricPassedBeforeRunning() = runTest {
    val bioHandler = FakeActionHandler("memory.wipe", PermissionLevel.BIOMETRIC)
    val g = gate(bioHandler)

    val decision = assertIs<ActionDecision.AwaitingUser>(g.submit(req(action = "memory.wipe")))
    val id = decision.proposal.id

    val withoutBio = g.confirm(id, biometricPassed = false)
    assertNotNull(withoutBio)
    assertEquals(ActionStatus.PENDING, withoutBio.status)
    assertEquals(0, bioHandler.runs)

    val withBio = g.confirm(id, biometricPassed = true)
    assertNotNull(withBio)
    assertEquals(ActionStatus.DONE, withBio.status)
    assertEquals(1, bioHandler.runs)
  }

  @Test
  fun stopAllDismissesPendingProposalsAndBlocksUntilResume() = runTest {
    val handler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM)
    val g = gate(handler)

    val pending = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))
    assertEquals(ActionStatus.PENDING, pending.proposal.status)

    g.stopAll()
    assertTrue(g.stopped.value)
    assertEquals(ActionStatus.DISMISSED, g.proposals.value.single().status)

    val blocked = assertIs<ActionDecision.Blocked>(
      g.submit(req(args = mapOf("title" to "นัดใหม่", "startUtcMs" to "300", "endUtcMs" to "400"))),
    )
    assertEquals(BlockReason.STOPPED, blocked.reason)
    assertEquals(ActionStatus.BLOCKED, audit.recent(1).single().status)

    g.resume()
    assertEquals(false, g.stopped.value)
    val afterResume = assertIs<ActionDecision.AwaitingUser>(
      g.submit(req(args = mapOf("title" to "นัดใหม่", "startUtcMs" to "300", "endUtcMs" to "400"))),
    )
    assertEquals(ActionStatus.PENDING, afterResume.proposal.status)
  }

  @Test
  fun rateLimitBlocksExcessSubmitsPerActionAndRecoversAfterWindow() = runTest {
    val calHandler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM)
    val autoHandler = FakeActionHandler("lookup.time", PermissionLevel.AUTO)
    val g = gate(calHandler, autoHandler)

    repeat(DefaultActionGate.MAX_SUBMITS_PER_WINDOW) { i ->
      val d = g.submit(req(args = mapOf("title" to "นัด $i", "startUtcMs" to "$i", "endUtcMs" to "${i + 1}")))
      assertIs<ActionDecision.AwaitingUser>(d)
    }

    val sixth = assertIs<ActionDecision.Blocked>(
      g.submit(req(args = mapOf("title" to "นัดเกินลิมิต", "startUtcMs" to "99", "endUtcMs" to "100"))),
    )
    assertEquals(BlockReason.RATE_LIMITED, sixth.reason)

    // Another action has its own independent rate-limit bucket
    assertIs<ActionDecision.Ran>(g.submit(req(action = "lookup.time")))

    // After the 60 s window expires, submits succeed again
    now += DefaultActionGate.RATE_LIMIT_WINDOW_MS
    val afterWindow = g.submit(req(args = mapOf("title" to "นัดหลังครบเวลา", "startUtcMs" to "500", "endUtcMs" to "600")))
    assertIs<ActionDecision.AwaitingUser>(afterWindow)
  }

  @Test
  fun doubleConcurrentConfirmRunsHandlerOnce() = runTest {
    val gateLatch = CompletableDeferred<Unit>()
    val handler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM) {
      gateLatch.await()
      ActionResultOutput(ok = true, message = "เพิ่มลงปฏิทินแล้ว")
    }
    val g = gate(handler)
    val pending = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))

    val first = async { g.confirm(pending.proposal.id) }
    val second = async { g.confirm(pending.proposal.id) }
    testScheduler.runCurrent()

    assertEquals(ActionStatus.RUNNING, g.proposals.value.single().status)
    gateLatch.complete(Unit)

    val r1 = first.await()
    val r2 = second.await()
    assertEquals(1, handler.runs)
    assertEquals(ActionStatus.DONE, g.proposals.value.single().status)
    assertEquals(ActionStatus.DONE, r1?.status)
    assertTrue(r2?.status == ActionStatus.RUNNING || r2?.status == ActionStatus.DONE)
  }

  @Test
  fun handlerThrowingMarksProposalFailedWithThaiMessageAndRethrowsCancellation() = runTest {
    var mode = "throw"
    val handler = FakeActionHandler(CalendarAddArgs.ACTION, PermissionLevel.CONFIRM) {
      when (mode) {
        "throw" -> throw IllegalStateException("db locked")
        "cancel" -> throw CancellationException("cancelled")
        "perm" -> ActionResultOutput(ok = false, message = "ต้องขอสิทธิ์", needsPermission = true)
        else -> ActionResultOutput(ok = true, message = "เพิ่มลงปฏิทินแล้ว")
      }
    }
    val g = gate(handler)
    val pending = assertIs<ActionDecision.AwaitingUser>(g.submit(req()))
    val id = pending.proposal.id

    val failed = g.confirm(id)
    assertNotNull(failed)
    assertEquals(ActionStatus.FAILED, failed.status)
    assertEquals(DefaultActionGate.HANDLER_ERROR_MESSAGE, failed.message)
    assertEquals(ActionStatus.FAILED, audit.recent(1).single().status)

    mode = "perm"
    val needsPerm = g.confirm(id)
    assertNotNull(needsPerm)
    assertEquals(ActionStatus.NEEDS_PERMISSION, needsPerm.status)

    mode = "cancel"
    assertFailsWith<CancellationException> { g.confirm(id) }

    // Put back in FAILED to test retry to DONE
    val pending2 = assertIs<ActionDecision.AwaitingUser>(
      g.submit(req(args = mapOf("title" to "นัด 2", "startUtcMs" to "300", "endUtcMs" to "400"))),
    )
    mode = "throw"
    assertEquals(ActionStatus.FAILED, g.confirm(pending2.proposal.id)?.status)
    mode = "ok"
    val retried = g.confirm(pending2.proposal.id)
    assertEquals(ActionStatus.DONE, retried?.status)
  }

  @Test
  fun unknownActionIsBlocked() = runTest {
    val g = gate()
    val decision = assertIs<ActionDecision.Blocked>(g.submit(req(action = "unknown.tool")))
    assertEquals(BlockReason.UNKNOWN_ACTION, decision.reason)
    assertEquals(ActionStatus.BLOCKED, audit.recent(1).single().status)
  }

  @Test
  fun calendarAddArgsRoundTripAndBadInputReturnsNull() {
    val proposal = AppointmentProposal(
      title = "นัดขูดหินปูน",
      startUtcMs = 1_761_883_200_000L,
      endUtcMs = 1_761_886_800_000L,
      dayLabel = "ศ 31 ต.ค.",
      timeLabel = "10:00–11:00",
      conflict = "ชนกับ “ประชุมทีมขาย” 09:30–10:30",
      status = AppointmentStatus.PENDING,
    )
    val request = CalendarAddArgs.toRequest(proposal, origin = ActionOrigin.CHAT_RULE)
    assertEquals(CalendarAddArgs.ACTION, request.action)
    assertEquals("นัดขูดหินปูน", request.title)
    assertEquals("ศ 31 ต.ค. · 10:00–11:00", request.detail)
    assertEquals(proposal.conflict, request.warning)

    val roundTripped = CalendarAddArgs.toProposal(request)
    assertEquals(proposal, roundTripped)

    val parsed = CalendarAddArgs.parse(request.arguments)
    assertEquals(CalendarAddInput("นัดขูดหินปูน", 1_761_883_200_000L, 1_761_886_800_000L), parsed)

    assertNull(CalendarAddArgs.parse(emptyMap()))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "   ", "startUtcMs" to "10", "endUtcMs" to "20")))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "นัด", "startUtcMs" to "bad", "endUtcMs" to "20")))
    assertNull(CalendarAddArgs.parse(mapOf("title" to "นัด", "startUtcMs" to "50", "endUtcMs" to "20")))
    assertNull(CalendarAddArgs.toProposal(request.copy(action = "other.action")))
    assertNull(CalendarAddArgs.toProposal(request.copy(detail = "ไม่มีตัวคั่น")))
  }
}
