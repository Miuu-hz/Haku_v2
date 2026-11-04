package com.bbpillow.haku.skill

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolMemoryStoreTest {
  private val opened = mutableListOf<SQLiteConnection>()

  @AfterTest fun close() = opened.forEach { it.close() }

  private fun openDb(): SQLiteConnection = BundledSQLiteDriver().open(":memory:").also(opened::add)

  private fun count(db: SQLiteConnection, table: String): Long =
    db.prepare("SELECT count(*) FROM $table").use { it.step(); it.getLong(0) }

  @Test fun roundTripEveryFieldWithThaiTextAndQuotesInValues() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    val args = mapOf(
      "query" to "ส้มตำ \"ปูปลาร้า\"\nเผ็ดน้อย",
      "shop" to "ร้าน 'แซ่บอีหลี' \\ สาขา 1",
      "note" to "เอาข้าวเหนียว 2 ห่อ\r\nไม่ใส่ชูรส",
    )
    val record = ToolResultRecord(
      key = "grab:search:k1",
      service = "grab_food",
      tool = "search_menu",
      arguments = args,
      text = "ร้านแซ่บอีหลี: ส้มตำ \"ปูปลาร้า\" 60 บาท\nพร้อมส่ง",
      fetchedUtcMs = 1700000000000L,
      freshUntilUtcMs = 1700086400000L,
    )
    store.put(record)

    val loaded = store.find("grab:search:k1")
    assertNotNull(loaded)
    assertEquals(record.key, loaded.key)
    assertEquals(record.service, loaded.service)
    assertEquals(record.tool, loaded.tool)
    assertEquals(record.arguments, loaded.arguments)
    assertEquals(record.text, loaded.text)
    assertEquals(record.fetchedUtcMs, loaded.fetchedUtcMs)
    assertEquals(record.freshUntilUtcMs, loaded.freshUntilUtcMs)
    assertEquals(record, loaded)
    assertEquals(1L, count(db, "tool_results"))
  }

  @Test fun argumentJsonSerializationRoundTrip() {
    val args = mapOf(
      "simple" to "plain",
      "thai" to "สวัสดีชาวโลก ร้านค้า 100%",
      "quotes" to "quote \"inside\" quote",
      "escapes" to "line1\nline2\r\nline3\ttab\\backslash",
      "empty" to "",
    )
    val encoded = encodeArguments(args)
    val decoded = decodeArguments(encoded)
    assertEquals(args, decoded)
    assertEquals(emptyMap(), decodeArguments(encodeArguments(emptyMap())))
    val awkward = mapOf("a&b=c%" to "x=1&y=%26", "=" to "&")
    assertEquals(awkward, decodeArguments(encodeArguments(awkward)))
  }

  @Test fun secondPutReplaces() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    val record1 = ToolResultRecord(
      key = "key1",
      service = "srv",
      tool = "tool1",
      arguments = mapOf("a" to "1"),
      text = "Original text",
      fetchedUtcMs = 1000L,
      freshUntilUtcMs = 2000L,
    )
    val record2 = ToolResultRecord(
      key = "key1",
      service = "srv",
      tool = "tool1",
      arguments = mapOf("a" to "2"),
      text = "Updated text with ร้านค้า",
      fetchedUtcMs = 1500L,
      freshUntilUtcMs = 3000L,
    )
    store.put(record1)
    assertEquals("Original text", store.find("key1")?.text)
    assertEquals(1L, count(db, "tool_results"))

    store.put(record2)
    val loaded = store.find("key1")
    assertEquals(record2, loaded)
    assertEquals(1L, count(db, "tool_results"))
  }

  @Test fun findForUnknownKeyIsNull() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    assertNull(store.find("non_existent_key"))
  }

  @Test fun toolMemoryIsFreshTrueBeforeAndFalseAtExactBoundary() {
    val record = ToolResultRecord(
      key = "k",
      service = "s",
      tool = "t",
      arguments = emptyMap(),
      text = "txt",
      fetchedUtcMs = 1000L,
      freshUntilUtcMs = 5000L,
    )
    assertTrue(ToolMemory.isFresh(record, nowUtcMs = 4999L))
    assertFalse(ToolMemory.isFresh(record, nowUtcMs = 5000L))
    assertFalse(ToolMemory.isFresh(record, nowUtcMs = 5001L))
  }

  @Test fun expireAllMakesFreshRecordStaleKeepsTextAndDoesNotMoveAlreadyStaleRecordForward() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    val now = 3000L

    val freshRecord = ToolResultRecord(
      key = "fresh_key",
      service = "srv",
      tool = "tool",
      arguments = mapOf("q" to "สด"),
      text = "ข้อความสด \"มีค่า\"",
      fetchedUtcMs = 1000L,
      freshUntilUtcMs = 5000L,
    )
    val staleRecord = ToolResultRecord(
      key = "stale_key",
      service = "srv",
      tool = "tool",
      arguments = mapOf("q" to "เก่า"),
      text = "ข้อความเก่า \"เดิม\"",
      fetchedUtcMs = 500L,
      freshUntilUtcMs = 1000L,
    )
    store.put(freshRecord)
    store.put(staleRecord)

    store.expireAll(now)

    val updatedFresh = store.find("fresh_key")
    assertNotNull(updatedFresh)
    assertEquals(now, updatedFresh.freshUntilUtcMs)
    assertEquals("ข้อความสด \"มีค่า\"", updatedFresh.text)
    assertFalse(ToolMemory.isFresh(updatedFresh, now))

    val updatedStale = store.find("stale_key")
    assertNotNull(updatedStale)
    assertEquals(1000L, updatedStale.freshUntilUtcMs)
    assertEquals("ข้อความเก่า \"เดิม\"", updatedStale.text)
    assertFalse(ToolMemory.isFresh(updatedStale, now))
  }

  @Test fun deleteFetchedBeforeRemovesOnlyOlderRowsWithCorrectCountAtExactBoundary() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    val cutoff = 2000L

    val older1 = ToolResultRecord("k1", "s", "t", emptyMap(), "old1", fetchedUtcMs = 1000L, freshUntilUtcMs = 5000L)
    val older2 = ToolResultRecord("k2", "s", "t", emptyMap(), "old2", fetchedUtcMs = 1999L, freshUntilUtcMs = 5000L)
    val exactBoundary = ToolResultRecord("k3", "s", "t", emptyMap(), "boundary", fetchedUtcMs = 2000L, freshUntilUtcMs = 5000L)
    val newer = ToolResultRecord("k4", "s", "t", emptyMap(), "new", fetchedUtcMs = 2001L, freshUntilUtcMs = 5000L)

    store.put(older1)
    store.put(older2)
    store.put(exactBoundary)
    store.put(newer)
    assertEquals(4L, count(db, "tool_results"))

    val removed = store.deleteFetchedBefore(cutoff)
    assertEquals(2, removed)
    assertEquals(2L, count(db, "tool_results"))

    assertNull(store.find("k1"))
    assertNull(store.find("k2"))
    assertEquals(exactBoundary, store.find("k3"))
    assertEquals(newer, store.find("k4"))
  }

  @Test fun clearEmpties() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    store.put(ToolResultRecord("k1", "s", "t", emptyMap(), "t1", 1000L, 2000L))
    store.put(ToolResultRecord("k2", "s", "t", emptyMap(), "t2", 1000L, 2000L))
    assertEquals(2L, count(db, "tool_results"))

    store.clear()
    assertEquals(0L, count(db, "tool_results"))
    assertNull(store.find("k1"))
    assertNull(store.find("k2"))
  }

  @Test fun aKeyMadeWithKeyOfIsSameRegardlessOfArgumentOrder() {
    val argsOrder1 = mapOf("shop" to "ร้าน ก", "item" to "ข้าวมันไก่", "amount" to "2")
    val argsOrder2 = mapOf("amount" to "2", "item" to "ข้าวมันไก่", "shop" to "ร้าน ก")
    val key1 = ToolMemory.keyOf("food_service", "query", argsOrder1)
    val key2 = ToolMemory.keyOf("food_service", "query", argsOrder2)
    assertEquals(key1, key2)
  }

  @Test fun valuesDifferingOnlyInShopNameGiveDifferentKeys() {
    val keyShopA = ToolMemory.keyOf("food", "search", mapOf("shop" to "ร้าน A", "item" to "ข้าวมันไก่"))
    val keyShopB = ToolMemory.keyOf("food", "search", mapOf("shop" to "ร้าน B", "item" to "ข้าวมันไก่"))
    assertTrue(keyShopA != keyShopB)
  }

  @Test fun parameterisedStatementsSafelyStoreSqlCharacters() {
    val db = openDb()
    val store = ToolMemoryStore(db)
    val sqlInjection = "'; DROP TABLE tool_results; --"
    val record = ToolResultRecord(
      key = sqlInjection,
      service = sqlInjection,
      tool = sqlInjection,
      arguments = mapOf("sql" to sqlInjection),
      text = sqlInjection,
      fetchedUtcMs = 1000L,
      freshUntilUtcMs = 2000L,
    )
    store.put(record)

    val loaded = store.find(sqlInjection)
    assertEquals(record, loaded)
    assertEquals(1L, count(db, "tool_results"))
  }
}
