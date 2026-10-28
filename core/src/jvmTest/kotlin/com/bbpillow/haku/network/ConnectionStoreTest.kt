package com.bbpillow.haku.network

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionStoreTest {
  private val db = BundledSQLiteDriver().open(":memory:")
  private val store = ConnectionStore(db)
  private val day = 86_400_000L
  private val t0 = 1_790_121_600_000L

  @AfterTest fun close() = db.close()

  private fun rec(at: Long, bytesIn: Long? = 5, result: String = "สำเร็จ") =
    ConnectionRecord.of(at, "https://huggingface.co/a/b.litertlm?download=true", ConnectionPurpose.MODEL_DOWNLOAD, bytesIn, null, result)

  @Test fun addAndReadNewestFirstWithNullBytes() {
    store.add(rec(t0))
    store.add(rec(t0 + 1, bytesIn = null, result = "หยุดไว้"))
    val rows = store.since(0)
    assertEquals(listOf("หยุดไว้", "สำเร็จ"), rows.map { it.result })
    assertEquals(null, rows[0].bytesIn)
    assertEquals(null, rows[0].bytesOut)
    assertEquals(rec(t0), rows[1])
    assertEquals(listOf(t0 + 1), store.since(t0 + 1).map { it.atEpochMs })
  }

  @Test fun addDropsEntriesOlderThanThirtyDays() {
    store.add(rec(t0))
    store.add(rec(t0 + 10 * day))
    store.add(rec(t0 + 31 * day))
    assertEquals(listOf(t0 + 31 * day, t0 + 10 * day), store.since(0).map { it.atEpochMs })
  }

  @Test fun clearEmptiesTheLog() {
    store.add(rec(t0))
    store.clear()
    assertTrue(store.since(0).isEmpty())
  }

  @Test fun onlyHostAndPathAreKept() {
    assertEquals("cas-bridge.xethub.hf.co" to "/xet/abc", ConnectionRecord.hostAndPath("https://u:pw@CAS-bridge.xethub.hf.co/xet/abc?X-Amz-Signature=s#f"))
    assertEquals("huggingface.co:443" to "/", ConnectionRecord.hostAndPath("https://huggingface.co:443?token=x"))
    val r = rec(t0)
    assertEquals("huggingface.co", r.host)
    assertEquals("/a/b.litertlm", r.path)
  }
}
