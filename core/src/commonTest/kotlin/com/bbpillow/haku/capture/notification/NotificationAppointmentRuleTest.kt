package com.bbpillow.haku.capture.notification

import com.bbpillow.haku.action.ActionDecision
import com.bbpillow.haku.action.ActionGate
import com.bbpillow.haku.action.ActionOrigin
import com.bbpillow.haku.action.ActionProposalRecord
import com.bbpillow.haku.action.ActionRequestInput
import com.bbpillow.haku.action.ActionStatus
import com.bbpillow.haku.action.PermissionLevel
import com.bbpillow.haku.memory.MemoryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class NotificationAppointmentRuleTest {
  private val tz = 420 // Asia/Bangkok

  // 2026-10-30 08:00 Bangkok, a Friday
  private val posted = MemoryFormat.parseUtc("2026-10-30T01:00:00.000Z")!!
  private var wallClock = posted
  private val rule = NotificationAppointmentRule { wallClock }

  private fun utc(s: String) = MemoryFormat.parseUtc(s)!!
  private fun chat(text: String, at: Long = posted, app: String = LINE) = NotificationTextEvent(app, at, text)

  @Test fun `allowlisted chat app gets an offer with gate arguments`() {
    val offer = rule.offer(chat("สมชาย\nมีนัดขูดหินปูน 10.00น. พรุ่งนี้"), tz)
    assertNotNull(offer)
    assertEquals("calendar.add", offer.action)
    assertEquals(ActionOrigin.NOTIFICATION_RULE, offer.origin)
    assertEquals("ขูดหินปูน", offer.title) // sender line is not part of the title
    assertEquals(
      mapOf(
        "title" to "ขูดหินปูน",
        "startUtcMs" to utc("2026-10-31T03:00:00.000Z").toString(),
        "endUtcMs" to utc("2026-10-31T04:00:00.000Z").toString(),
      ),
      offer.arguments,
    )
    assertTrue(offer.detail.endsWith(" · 10:00–11:00"), offer.detail)
  }

  @Test fun `every allowlisted app is accepted`() {
    NotificationAppointmentRule.CHAT_APPS.forEachIndexed { i, app ->
      assertNotNull(rule.offer(chat("x\nนัดประชุม 10:0$i พรุ่งนี้", app = app), tz), app)
    }
  }

  @Test fun `apps outside the allowlist are ignored`() {
    assertNull(rule.offer(chat("x\nมีนัดประชุม 10:00 พรุ่งนี้", app = "com.kbank.kplus"), tz))
    assertNull(rule.offer(chat("x\nมีนัดประชุม 10:00 พรุ่งนี้", app = "shared"), tz))
  }

  @Test fun `our own package is skipped`() {
    assertNull(rule.offer(chat("x\nมีนัดประชุม 10:00 พรุ่งนี้", app = NotificationAppointmentRule.OWN_PACKAGE), tz))
  }

  @Test fun `anchor is postedUtcMs not the wall clock`() {
    // sent 23:59 Bangkok on Fri 30 Oct, read the next morning (08:00 Sat 31 Oct)
    val sent = utc("2026-10-30T16:59:00.000Z")
    wallClock = utc("2026-10-31T01:00:00.000Z")
    val offer = rule.offer(chat("แพรว\nนัดประชุม 10:00 พรุ่งนี้", at = sent), tz)
    assertNotNull(offer)
    // "พรุ่งนี้" = Sat 31 Oct (the day after it was sent), not Sun 1 Nov
    assertEquals(utc("2026-10-31T03:00:00.000Z").toString(), offer.arguments["startUtcMs"])
  }

  @Test fun `appointment already in the past is skipped`() {
    val sent = utc("2026-10-30T16:59:00.000Z")
    wallClock = utc("2026-10-31T03:00:00.000Z") // read at 10:00 Saturday, the 10:00 meeting has started
    assertNull(rule.offer(chat("แพรว\nนัดประชุม 10:00 พรุ่งนี้", at = sent), tz))
  }

  @Test fun `chit-chat and questions give nothing`() {
    listOf(
      "สวัสดีครับ วันนี้อากาศดี",
      "พรุ่งนี้มีนัดขูดหินปูนกี่โมง",
      "มีนัดหมอ 10 โมงมั้ย",
      "ซื้อไข่ไก่ นม ขนมปัง",
      "เมื่อวานไปประชุมมา 10 โมง",
      "พรุ่งนี้มีนัดขูดหินปูน", // no clock time
    ).forEach { assertNull(rule.offer(chat("x\n$it"), tz), it) }
  }

  @Test fun `same message re-posted gives one offer`() {
    assertNotNull(rule.offer(chat("สมชาย\nมีนัดพบแพทย์ 10:30 พรุ่งนี้"), tz))
    assertNull(rule.offer(chat("สมชาย\nมีนัดพบแพทย์ 10:30 พรุ่งนี้"), tz)) // notification update
    assertNull(rule.offer(chat("กลุ่มครอบครัว\nมีนัดพบแพทย์ 10:30 พรุ่งนี้"), tz)) // group summary, other title line
    assertNotNull(rule.offer(chat("สมชาย\nมีนัดพบแพทย์ 11:30 พรุ่งนี้"), tz)) // different time = different offer
  }

  @Test fun `guard forgets the oldest key after RECENT_OFFER_MAX offers`() {
    val max = NotificationAppointmentRule.RECENT_OFFER_MAX
    fun minute(i: Int) = chat("x\nนัดประชุม 10:${i.toString().padStart(2, '0')} พรุ่งนี้")
    assertNotNull(rule.offer(minute(0), tz))
    for (i in 1..max) assertNotNull(rule.offer(minute(i), tz))
    assertNotNull(rule.offer(minute(0), tz)) // pushed out, offered again
    assertNull(rule.offer(minute(max), tz)) // still remembered
  }

  @Test fun `selected text skips the allowlist and uses SHARED_TEXT`() {
    val offer = rule.offerSelected(chat("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", app = "shared"), tz)
    assertNotNull(offer)
    assertEquals(ActionOrigin.SHARED_TEXT, offer.origin)
    assertEquals("ขูดหินปูน", offer.title)
    assertNotNull(rule.offer(chat("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", app = NotificationAppointmentRule.OWN_PACKAGE), tz, ActionOrigin.SHARED_TEXT))
    assertNull(rule.offerSelected(chat("สวัสดีครับ", app = "shared"), tz))
  }

  @Test fun `offer submits to a fake ActionGate and confirms or dismisses by proposal id`() = runTest {
    val gate = FakeActionGate()
    val offer = assertNotNull(rule.offer(chat("สมชาย\nมีนัดขูดหินปูน 10.00น. พรุ่งนี้"), tz))
    val decision = gate.submit(offer) as ActionDecision.AwaitingUser
    assertEquals(ActionStatus.PENDING, decision.proposal.status)
    assertEquals("ส 31 ต.ค. · 10:00–11:00", decision.proposal.request.detail)
    assertTrue(gate.confirmed.isEmpty())

    // Same title+start via selected text shares dedupeKey, so a pending proposal is reused instead of duplicated.
    val viaSelection = assertNotNull(rule.offerSelected(chat("มีนัดขูดหินปูน 10.00น. พรุ่งนี้", app = "shared"), tz))
    assertEquals(offer.dedupeKey, viaSelection.dedupeKey)
    assertEquals(decision.proposal.id, (gate.submit(viaSelection) as ActionDecision.AwaitingUser).proposal.id)

    val done = assertNotNull(gate.confirm(decision.proposal.id))
    assertEquals(ActionStatus.DONE, done.status)
    assertEquals(listOf(offer.arguments), gate.confirmed)
    assertNull(gate.confirm("expired-id"))
  }

  private class FakeActionGate : ActionGate {
    override val proposals = MutableStateFlow<List<ActionProposalRecord>>(emptyList())
    val confirmed = mutableListOf<Map<String, String>>()

    override suspend fun submit(request: ActionRequestInput): ActionDecision {
      proposals.value.firstOrNull { it.status == ActionStatus.PENDING && it.request.dedupeKey == request.dedupeKey }
        ?.let { return ActionDecision.AwaitingUser(it) }
      val proposal = ActionProposalRecord(
        id = "p-${proposals.value.size + 1}",
        request = request,
        level = PermissionLevel.CONFIRM,
        status = ActionStatus.PENDING,
        createdUtcMs = 0L,
      )
      proposals.value = proposals.value + proposal
      return ActionDecision.AwaitingUser(proposal)
    }

    override suspend fun confirm(proposalId: String, biometricPassed: Boolean): ActionProposalRecord? {
      val current = proposals.value.firstOrNull { it.id == proposalId } ?: return null
      confirmed += current.request.arguments
      val done = current.copy(status = ActionStatus.DONE, message = "เพิ่มลงปฏิทินแล้ว")
      proposals.value = proposals.value.map { if (it.id == proposalId) done else it }
      return done
    }

    override fun dismiss(proposalId: String) {
      proposals.value = proposals.value.map { if (it.id == proposalId) it.copy(status = ActionStatus.DISMISSED) else it }
    }

    override fun stopAll() = Unit
    override fun resume() = Unit
  }

  private companion object {
    const val LINE = "jp.naver.line.android"
  }
}
