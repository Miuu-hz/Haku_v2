package com.bbpillow.haku.memory.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline

/**
 * All search-index SQL. Two tables: `records` (original + normalized text) and FTS5 `idx` holding the
 * segmented copy (words joined by spaces), linked by rowid. Not thread-safe: callers serialize access.
 * FTS5 tokenizer note: unicode61 treats Thai vowel/tone marks (Mn/Mc) as separators unless told otherwise.
 */
class SearchIndexStore(
  private val db: SQLiteConnection,
  private val pipeline: TextPipeline,
  private val synonyms: SynonymTable? = null,
) : SearchRepository {
  init {
    db.execSQL("CREATE TABLE IF NOT EXISTS records(id TEXT NOT NULL UNIQUE, text TEXT NOT NULL, normalized TEXT NOT NULL)")
    db.execSQL(
      "CREATE VIRTUAL TABLE IF NOT EXISTS idx USING fts5(segmented, " +
        "tokenize = \"unicode61 remove_diacritics 0 categories 'L* N* Co Mn Mc'\")",
    )
  }

  override fun index(input: IndexRecordInput) = inTransaction { upsert(input) }

  override fun indexAll(inputs: List<IndexRecordInput>) = inTransaction { inputs.forEach(::upsert) }

  override fun remove(id: String): Boolean = inTransaction { deleteById(id) }

  override fun count(): Int = query("SELECT count(*) FROM records", emptyList()) { it.getLong(0).toInt() }.first()

  override fun search(query: SearchQueryInput): List<SearchHitOutput> {
    val processed = pipeline.process(TextInput(query.text, query.languageCode))
    if (processed.tokens.isEmpty()) return emptyList()
    val match = matchExpression(processed.tokens.map { it.text }, query)
    val ranked = query(
      "SELECT r.id, r.text, bm25(idx) FROM idx JOIN records r ON r.rowid = idx.rowid " +
        "WHERE idx MATCH ? ORDER BY bm25(idx) LIMIT ?",
      listOf(match, query.limit.toLong()),
    ) { SearchHitOutput(it.getText(0), it.getText(1), -it.getDouble(2), MatchKind.WORDS) }
    if (ranked.isNotEmpty()) return ranked
    // D5 fallback: the segmenter may have split the query differently from the record.
    // ponytail: full scan (fine at ~10k rows); lower() is ASCII-only in SQLite.
    return query(
      "SELECT id, text FROM records WHERE instr(lower(normalized), ?) > 0 LIMIT ?",
      listOf(processed.normalized.lowercase(), query.limit.toLong()),
    ) { SearchHitOutput(it.getText(0), it.getText(1), 0.0, MatchKind.SUBSTRING) }
  }

  // Every query word must match (AND); with synonyms each word is (word OR synonym ...), synonyms as phrases.
  private fun matchExpression(words: List<String>, query: SearchQueryInput): String = words.joinToString(" AND ") { word ->
    val variants = if (query.expandSynonyms && synonyms != null) synonyms.expand(word) else setOf(word)
    val phrases = variants.map { pipeline.process(TextInput(it, query.languageCode)).segmented }.filter { it.isNotBlank() }
    phrases.joinToString(" OR ", "(", ")") { "\"" + it.replace("\"", "\"\"") + "\"" }
  }

  private fun upsert(input: IndexRecordInput) {
    deleteById(input.id)
    val p = pipeline.process(TextInput(input.text, input.languageCode))
    exec("INSERT INTO records(id, text, normalized) VALUES(?, ?, ?)", listOf(input.id, input.text, p.normalized))
    exec("INSERT INTO idx(rowid, segmented) VALUES(last_insert_rowid(), ?)", listOf(p.segmented))
  }

  private fun deleteById(id: String): Boolean {
    val rowid = query("SELECT rowid FROM records WHERE id = ?", listOf(id)) { it.getLong(0) }.firstOrNull() ?: return false
    exec("DELETE FROM idx WHERE rowid = ?", listOf(rowid))
    exec("DELETE FROM records WHERE rowid = ?", listOf(rowid))
    return true
  }

  private fun <T> inTransaction(block: () -> T): T {
    db.execSQL("BEGIN")
    try {
      return block().also { db.execSQL("COMMIT") }
    } catch (e: Throwable) {
      db.execSQL("ROLLBACK")
      throw e
    }
  }

  private fun exec(sql: String, args: List<Any>) {
    db.prepare(sql).use { bind(it, args); it.step() }
  }

  private fun <T> query(sql: String, args: List<Any>, row: (SQLiteStatement) -> T): List<T> =
    db.prepare(sql).use { st ->
      bind(st, args)
      buildList { while (st.step()) add(row(st)) }
    }

  private fun bind(st: SQLiteStatement, args: List<Any>) = args.forEachIndexed { i, a ->
    when (a) {
      is Long -> st.bindLong(i + 1, a)
      else -> st.bindText(i + 1, a as String)
    }
  }
}
