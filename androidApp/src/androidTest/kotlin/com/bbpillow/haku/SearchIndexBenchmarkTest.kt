package com.bbpillow.haku

import android.util.Log
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.SearchQueryInput
import com.bbpillow.haku.memory.index.SyntheticDiary
import com.bbpillow.haku.platform.IcuWordSegmenter
import kotlin.time.TimeSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P0.4 piece 2 on the real device: ICU segmenter + bundled SQLite, 10k synthetic rows. Read logcat tag HakuIndex. */
@RunWith(AndroidJUnit4::class)
class SearchIndexBenchmarkTest {
  @Test fun tenThousandRecordsSearchUnder50msP95() {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val file = ctx.getDatabasePath("haku-index-bench.db").also { it.parentFile?.mkdirs(); it.delete() }
    val db = BundledSQLiteDriver().open(file.path)
    try {
      val store = SearchIndexStore(db, TextPipeline(IcuWordSegmenter()))
      val records = SyntheticDiary.records(10_000)
      val build = TimeSource.Monotonic.markNow()
      store.indexAll(records)
      Log.i(TAG, "indexed ${store.count()} rows in ${build.elapsedNow()}")

      val queries = SyntheticDiary.queries(50)
      repeat(5) { store.search(SearchQueryInput(queries[it])) }
      val ms = queries.map { q ->
        val t = TimeSource.Monotonic.markNow()
        store.search(SearchQueryInput(q))
        t.elapsedNow().inWholeNanoseconds / 1_000_000.0
      }.sorted()
      val p50 = ms[ms.size / 2]
      val p95 = ms[ms.size * 95 / 100]
      Log.i(TAG, "10000 rows, ${ms.size} queries: p50=%.2f ms p95=%.2f ms max=%.2f ms".format(p50, p95, ms.last()))
      assertTrue("p95 $p95 ms", p95 < 50.0)
    } finally {
      db.close()
      file.delete()
    }
  }

  private companion object { const val TAG = "HakuIndex" }
}
