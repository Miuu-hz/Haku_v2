package com.bbpillow.haku.action

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditStoreTest {
  private val db = BundledSQLiteDriver().open(":memory:")
  private val store = AuditStore(db)
  private val day = 86_400_000L
  private val t0 = 1_790_121_600_000L

  @AfterTest
  fun close() = db.close()

  private fun rec(
    proposalId: String = "p-1",
    at: Long = t0,
    status: ActionStatus = ActionStatus.PENDING,
    origin: ActionOrigin = ActionOrigin.MODEL_TOOL,
    level: PermissionLevel = PermissionLevel.CONFIRM,
    title: String = "นัดขูดหินปูน",
  ) = AuditRecord(
    proposalId = proposalId,
    action = CalendarAddArgs.ACTION,
    origin = origin,
    level = level,
    status = status,
    title = title,
    atUtcMs = at,
  )

  @Test
  fun appendAndReadNewestFirstRespectingLimit() {
    val r1 = rec("p-1", t0, ActionStatus.PENDING)
    val r2 = rec("p-1", t0 + 1, ActionStatus.RUNNING)
    val r3 = rec("p-1", t0 + 2, ActionStatus.DONE)
    store.append(r1)
    store.append(r2)
    store.append(r3)

    assertEquals(listOf(r3, r2, r1), store.recent(10))
    assertEquals(listOf(r3, r2), store.recent(2))
    assertTrue(store.recent(0).isEmpty())
  }

  @Test
  fun appendDropsEntriesOlderThanNinetyDays() {
    store.append(rec("p-old", t0))
    store.append(rec("p-mid", t0 + 30 * day))
    store.append(rec("p-new", t0 + 91 * day))

    assertEquals(
      listOf(t0 + 91 * day, t0 + 30 * day),
      store.recent(10).map { it.atUtcMs },
    )
  }

  @Test
  fun clearEmptiesTheAuditLog() {
    store.append(rec("p-1", t0))
    store.clear()
    assertTrue(store.recent(10).isEmpty())
  }
}
