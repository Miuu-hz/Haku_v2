package com.bbpillow.haku.action

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingOfferStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun openDb(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also(opened::add)

  @Test fun roundTripIncludingNullAppNameAndNullWarning() {
    val store = PendingOfferStore(openDb())
    val record1 = PendingOfferRecord(
      id = "off1",
      dedupeKey = "dedupe1",
      title = "Dentist Appointment",
      detail = "Fri 31 Oct · 10:00-11:00",
      startUtcMs = 1700000000000L,
      endUtcMs = 1700003600000L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = null,
      warning = null,
      createdUtcMs = 1699990000000L,
      expiresUtcMs = 1700003600000L,
    )
    val record2 = PendingOfferRecord(
      id = "off2",
      dedupeKey = "dedupe2",
      title = "Team Sync",
      detail = "Sat 1 Nov · 14:00-15:00",
      startUtcMs = 1700100000000L,
      endUtcMs = 1700103600000L,
      allDay = true,
      origin = ActionOrigin.CHAT_RULE,
      appName = "ChatApp",
      warning = "Conflict with previous event",
      createdUtcMs = 1699990001000L,
      expiresUtcMs = 1700103600000L,
    )
    store.put(record1)
    store.put(record2)

    val loaded1 = store.find("off1")
    val loaded2 = store.find("off2")

    assertEquals(record1, loaded1)
    assertEquals(record2, loaded2)
  }

  @Test fun sameDedupeKeyUnderANewIdReplacesAndLeavesOneRow() {
    val db = openDb()
    val store = PendingOfferStore(db)

    val initial = PendingOfferRecord(
      id = "prop_old",
      dedupeKey = "calendar.add:appointment_1",
      title = "Doctor Visit",
      detail = "Fri 10:00",
      startUtcMs = 1000L,
      endUtcMs = 2000L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = "MessageApp",
      warning = null,
      createdUtcMs = 500L,
      expiresUtcMs = 2000L,
    )
    store.put(initial)
    assertEquals(initial, store.find("prop_old"))

    val updated = PendingOfferRecord(
      id = "prop_new",
      dedupeKey = "calendar.add:appointment_1",
      title = "Doctor Visit (Updated)",
      detail = "Fri 10:30",
      startUtcMs = 1500L,
      endUtcMs = 2500L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = "MessageApp",
      warning = "Rescheduled",
      createdUtcMs = 800L,
      expiresUtcMs = 2500L,
    )
    store.put(updated)

    assertNull(store.find("prop_old"))
    assertEquals(updated, store.find("prop_new"))

    val count = db.prepare("SELECT count(*) FROM pending_offers").use { it.step(); it.getLong(0) }
    assertEquals(1L, count)
  }

  @Test fun activeExcludesExpiredAndIsOrderedByStart() {
    val store = PendingOfferStore(openDb())
    val now = 2000L

    val expired = PendingOfferRecord(
      id = "exp",
      dedupeKey = "key_exp",
      title = "Old Meeting",
      detail = "d",
      startUtcMs = 1000L,
      endUtcMs = 1999L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = null,
      warning = null,
      createdUtcMs = 500L,
      expiresUtcMs = 2000L,
    )
    val activeLater = PendingOfferRecord(
      id = "later",
      dedupeKey = "key_later",
      title = "Later Meeting",
      detail = "d",
      startUtcMs = 6000L,
      endUtcMs = 7000L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = null,
      warning = null,
      createdUtcMs = 500L,
      expiresUtcMs = 7000L,
    )
    val activeEarlier = PendingOfferRecord(
      id = "earlier",
      dedupeKey = "key_earlier",
      title = "Earlier Meeting",
      detail = "d",
      startUtcMs = 3000L,
      endUtcMs = 4000L,
      allDay = false,
      origin = ActionOrigin.NOTIFICATION_RULE,
      appName = null,
      warning = null,
      createdUtcMs = 500L,
      expiresUtcMs = 4000L,
    )

    store.put(expired)
    store.put(activeLater)
    store.put(activeEarlier)

    val active = store.active(now)
    assertEquals(listOf(activeEarlier, activeLater), active)
  }

  @Test fun deleteExpiredCount() {
    val store = PendingOfferStore(openDb())
    val now = 3000L

    val expired1 = PendingOfferRecord("e1", "k1", "T1", "d", 1000L, 2000L, false, ActionOrigin.NOTIFICATION_RULE, null, null, 100L, 2000L)
    val expired2 = PendingOfferRecord("e2", "k2", "T2", "d", 1500L, 3000L, false, ActionOrigin.NOTIFICATION_RULE, null, null, 200L, 3000L)
    val active = PendingOfferRecord("a1", "k3", "T3", "d", 3500L, 4500L, false, ActionOrigin.NOTIFICATION_RULE, null, null, 300L, 3001L)

    store.put(expired1)
    store.put(expired2)
    store.put(active)

    val deleted = store.deleteExpired(now)
    assertEquals(2, deleted)

    assertNull(store.find("e1"))
    assertNull(store.find("e2"))
    assertEquals(active, store.find("a1"))
  }

  @Test fun badStoredOriginFallsBack() {
    val db = openDb()
    val store = PendingOfferStore(db)

    db.prepare(
      "INSERT INTO pending_offers(id, dedupe_key, title, detail, start_ms, end_ms, all_day, origin, app_name, warning, created_ms, expires_ms) " +
        "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).use { st ->
      st.bindText(1, "bad_origin_id")
      st.bindText(2, "bad_origin_key")
      st.bindText(3, "Title")
      st.bindText(4, "Detail")
      st.bindLong(5, 1000L)
      st.bindLong(6, 2000L)
      st.bindLong(7, 0L)
      st.bindText(8, "LEGACY_OR_UNKNOWN_ORIGIN")
      st.bindNull(9)
      st.bindNull(10)
      st.bindLong(11, 500L)
      st.bindLong(12, 2000L)
      st.step()
    }

    val loaded = store.find("bad_origin_id")
    assertNotNull(loaded)
    assertEquals(ActionOrigin.NOTIFICATION_RULE, loaded.origin)
  }

  @Test fun pendingOffersOfThenToRequestGivesBackSameArgumentsDedupeKeyOrigin() {
    val args = mapOf(
      CalendarAddArgs.KEY_TITLE to "Project Review",
      CalendarAddArgs.KEY_START_UTC_MS to "1700000000000",
      CalendarAddArgs.KEY_END_UTC_MS to "1700003600000",
      CalendarAddArgs.KEY_ALL_DAY to "true",
    )
    val request = ActionRequestInput(
      action = CalendarAddArgs.ACTION,
      arguments = args,
      title = "Project Review",
      detail = "Thu 15 Nov · 10:00-11:00",
      origin = ActionOrigin.NOTIFICATION_RULE,
      warning = "Potential overlap",
      dedupeKey = "dedupe:review:1",
    )
    val proposal = ActionProposalRecord(
      id = "prop_123",
      request = request,
      level = PermissionLevel.CONFIRM,
      status = ActionStatus.PENDING,
      createdUtcMs = 1699990000000L,
    )

    val offer = PendingOffers.of(proposal, appName = "ChatApp", nowUtcMs = 1699990000000L)
    assertNotNull(offer)
    assertEquals("prop_123", offer.id)
    assertEquals("dedupe:review:1", offer.dedupeKey)
    assertEquals("Project Review", offer.title)
    assertEquals("Thu 15 Nov · 10:00-11:00", offer.detail)
    assertEquals(1700000000000L, offer.startUtcMs)
    assertEquals(1700003600000L, offer.endUtcMs)
    assertTrue(offer.allDay)
    assertEquals(ActionOrigin.NOTIFICATION_RULE, offer.origin)
    assertEquals("ChatApp", offer.appName)
    assertEquals("Potential overlap", offer.warning)

    val restoredRequest = PendingOffers.toRequest(offer)
    assertEquals(request.action, restoredRequest.action)
    assertEquals(request.arguments, restoredRequest.arguments)
    assertEquals(request.dedupeKey, restoredRequest.dedupeKey)
    assertEquals(request.origin, restoredRequest.origin)
    assertEquals(request.title, restoredRequest.title)
    assertEquals(request.detail, restoredRequest.detail)
    assertEquals(request.warning, restoredRequest.warning)
  }

  @Test fun pendingOffersOfReturnsNullForActionNotCalendarAdd() {
    val nonCalendarRequest = ActionRequestInput(
      action = "notes.add",
      arguments = mapOf("text" to "Quick note"),
      title = "Quick note",
      detail = "Note detail",
      origin = ActionOrigin.CHAT_RULE,
    )
    val proposal = ActionProposalRecord(
      id = "prop_note",
      request = nonCalendarRequest,
      level = PermissionLevel.CONFIRM,
      status = ActionStatus.PENDING,
      createdUtcMs = 1000L,
    )

    val offer = PendingOffers.of(proposal, null, 1000L)
    assertNull(offer)
  }
}
