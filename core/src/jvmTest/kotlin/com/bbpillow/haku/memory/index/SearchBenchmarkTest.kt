package com.bbpillow.haku.memory.index

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.language.TextPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** P0.4 piece 2 pass criterion on the host. Timing here is indicative only; the real number comes from the device benchmark. */
class SearchBenchmarkTest {
  @Test fun tenThousandRecordsSearchUnder50msP95() {
    val db = BundledSQLiteDriver().open(":memory:")
    try {
      val store = SearchIndexStore(db, TextPipeline(DictionarySegmenter(SyntheticDiary.thaiVocabulary)))
      val records = SyntheticDiary.records(10_000)
      store.indexAll(records)
      assertEquals(10_000, store.count())
      assertEquals(records, SyntheticDiary.records(10_000), "generator must be deterministic")

      val queries = SyntheticDiary.queries(50)
      repeat(5) { store.search(SearchQueryInput(queries[it])) } // warm-up
      val ms = queries.map { q ->
        val t = TimeSource.Monotonic.markNow()
        val hits = store.search(SearchQueryInput(q))
        val elapsed = t.elapsedNow().inWholeNanoseconds / 1_000_000.0
        assertTrue(hits.isNotEmpty(), "query '$q' should hit")
        elapsed
      }.sorted()
      val p50 = ms[ms.size / 2]
      val p95 = ms[(ms.size * 95 / 100).coerceAtMost(ms.size - 1)]
      println("HakuIndex host benchmark: 10000 rows, ${ms.size} queries, p50=%.2f ms p95=%.2f ms max=%.2f ms".format(p50, p95, ms.last()))
      assertTrue(p95 < 50.0, "p95 $p95 ms")
    } finally {
      db.close()
    }
  }
}
