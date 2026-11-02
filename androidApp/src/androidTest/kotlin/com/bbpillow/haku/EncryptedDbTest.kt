package com.bbpillow.haku

import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.SearchQueryInput
import com.bbpillow.haku.memory.index.SyntheticDiary
import com.bbpillow.haku.platform.DataKeys
import com.bbpillow.haku.platform.IcuWordSegmenter
import java.io.File
import kotlin.time.TimeSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** D11 on a device/emulator: plaintext → SQLCipher conversion, then the real FTS5 index on the encrypted DB. Logcat tag HakuD11. */
@RunWith(AndroidJUnit4::class)
class EncryptedDbTest {
  private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

  private fun fresh(name: String) = File(ctx.noBackupFilesDir, name).also { f ->
    listOf("", "-wal", "-shm", "-journal", ".enc", ".plain").forEach { File(f.path + it).delete() }
  }

  @Test fun convertsPlaintextAndKeepsRows() {
    val f = fresh("d11-convert.db")
    BundledSQLiteDriver().open(f.path).use { db ->
      db.execSQL("CREATE TABLE t(x TEXT, b BLOB)")
      db.prepare("INSERT INTO t VALUES (?, ?)").use { it.bindText(1, "ไปหาหมอฟัน"); it.bindBlob(2, byteArrayOf(1, 2, 3)); it.step() }
    }
    DataKeys.openDatabase(ctx, f).use { db ->
      db.prepare("SELECT x, b FROM t").use {
        assertTrue(it.step())
        assertEquals("ไปหาหมอฟัน", it.getText(0))
        assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(1))
      }
    }
    val header = ByteArray(16).also { b -> f.inputStream().use { it.read(b) } }
    assertFalse(header.decodeToString().startsWith("SQLite format 3"))
    assertFalse(f.readBytes().decodeToString().contains("ไปหาหมอฟัน"))
    // reopen with the stored key
    DataKeys.openDatabase(ctx, f).use { db -> db.prepare("SELECT count(*) FROM t").use { it.step(); assertEquals(1L, it.getLong(0)) } }
  }

  @Test fun finishesASwapInterruptedByACrash() {
    val f = fresh("d11-crash.db")
    BundledSQLiteDriver().open(f.path).use { db -> db.execSQL("CREATE TABLE t(x TEXT)"); db.execSQL("INSERT INTO t VALUES ('a')") }
    DataKeys.openDatabase(ctx, f).close()
    // state right after "plaintext moved aside", before ".enc moved in"
    val plain = File(f.path + ".plain").apply { writeText("SQLite format 3") }
    check(f.renameTo(File(f.path + ".enc")))
    DataKeys.openDatabase(ctx, f).use { db -> db.prepare("SELECT x FROM t").use { assertTrue(it.step()); assertEquals("a", it.getText(0)) } }
    assertFalse(plain.exists())
    assertFalse(File(f.path + ".enc").exists())
  }

  @Test fun searchIndexWorksOnSqlcipher() {
    val f = fresh("d11-index.db")
    DataKeys.openDatabase(ctx, f).use { db ->
      val store = SearchIndexStore(db, TextPipeline(IcuWordSegmenter()))
      val t = TimeSource.Monotonic.markNow()
      store.indexAll(SyntheticDiary.records(10_000))
      Log.i(TAG, "indexed ${store.count()} rows in ${t.elapsedNow()}")
      assertEquals(10_000, store.count())
      val ms = SyntheticDiary.queries(50).map { q ->
        val m = TimeSource.Monotonic.markNow()
        store.search(SearchQueryInput(q))
        m.elapsedNow().inWholeNanoseconds / 1_000_000.0
      }.sorted()
      Log.i(TAG, "sqlcipher 10000 rows: p50=%.2f ms p95=%.2f ms".format(ms[ms.size / 2], ms[ms.size * 95 / 100]))
    }
  }

  private companion object { const val TAG = "HakuD11" }
}
