package com.bbpillow.haku.memory.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.bbpillow.haku.language.SynonymTable
import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.Provenance

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
    // ponytail: the index is derived data; a schema change means delete the db file and rebuild from memory files.
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS records(id TEXT NOT NULL UNIQUE, text TEXT NOT NULL, normalized TEXT NOT NULL, " +
        "level TEXT, grp TEXT, mid TEXT, provenance TEXT, at_ms INTEGER)",
    )
    db.execSQL(
      "CREATE VIRTUAL TABLE IF NOT EXISTS idx USING fts5(segmented, " +
        "tokenize = \"unicode61 remove_diacritics 0 categories 'L* N* Co Mn Mc'\")",
    )
  }

  override fun index(input: IndexRecordInput) = inTransaction { upsert(input) }

  override fun indexAll(inputs: List<IndexRecordInput>) = inTransaction { inputs.forEach(::upsert) }

  override fun remove(id: String): Boolean = inTransaction { deleteById(id) }

  override fun count(): Int = query("SELECT count(*) FROM records", emptyList()) { it.getLong(0).toInt() }.first()

  override fun removeGroup(level: MemoryLevel, group: String): Int = inTransaction {
    val rowids = query("SELECT rowid FROM records WHERE level = ? AND grp = ?", listOf(level.name, group)) { it.getLong(0) }
    rowids.forEach { exec("DELETE FROM idx WHERE rowid = ?", listOf(it)); exec("DELETE FROM records WHERE rowid = ?", listOf(it)) }
    rowids.size
  }

  override fun clear() = inTransaction {
    exec("DELETE FROM idx", emptyList())
    exec("DELETE FROM records", emptyList())
  }

  override fun search(query: SearchQueryInput): List<SearchHitOutput> {
    val processed = pipeline.process(TextInput(query.text, query.languageCode))
    if (processed.tokens.isEmpty()) return emptyList()
    val match = matchExpression(processed.tokens.map { it.text }, query)
    val (filter, filterArgs) = filterSql(query)
    val ranked = query(
      "SELECT r.id, r.text, bm25(idx), r.level, r.grp, r.mid, r.provenance, r.at_ms FROM idx JOIN records r ON r.rowid = idx.rowid " +
        "WHERE idx MATCH ?$filter ORDER BY bm25(idx) LIMIT ?",
      listOf(match) + filterArgs + listOf(query.limit.toLong()),
    ) { hit(it, -it.getDouble(2), MatchKind.WORDS) }
    if (ranked.isNotEmpty()) return ranked
    // D5 fallback: the segmenter may have split the query differently from the record.
    // ponytail: full scan (fine at ~10k rows); lower() is ASCII-only in SQLite.
    return query(
      "SELECT r.id, r.text, 0, r.level, r.grp, r.mid, r.provenance, r.at_ms FROM records r WHERE instr(lower(r.normalized), ?) > 0$filter LIMIT ?",
      listOf(processed.normalized.lowercase()) + filterArgs + listOf(query.limit.toLong()),
    ) { hit(it, 0.0, MatchKind.SUBSTRING) }
  }

  private fun hit(st: SQLiteStatement, score: Double, kind: MatchKind): SearchHitOutput {
    val memory = if (st.isNull(3)) null else {
      val prov = Provenance.fromKey(st.getText(6)) ?: Provenance.EXTERNAL // unknown stamp = least trusted
      IndexedMemory(MemoryRef(MemoryLevel.valueOf(st.getText(3)), st.getText(4), st.getText(5)), prov, st.getLong(7))
    }
    return SearchHitOutput(st.getText(0), st.getText(1), score, kind, memory)
  }

  private fun filterSql(q: SearchQueryInput): Pair<String, List<Any>> {
    val sql = StringBuilder()
    val args = mutableListOf<Any>()
    fun <T> inList(column: String, values: Set<T>?, key: (T) -> String) {
      if (values == null) return
      if (values.isEmpty()) { sql.append(" AND 0"); return }
      sql.append(" AND r.$column IN (").append(values.joinToString(",") { "?" }).append(")")
      values.forEach { args += key(it) }
    }
    inList("level", q.levels) { it.name }
    inList("provenance", q.provenances) { it.key }
    q.fromEpochMs?.let { sql.append(" AND r.at_ms >= ?"); args += it }
    q.toEpochMs?.let { sql.append(" AND r.at_ms < ?"); args += it }
    return sql.toString() to args
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
    val m = input.memory
    exec(
      "INSERT INTO records(id, text, normalized, level, grp, mid, provenance, at_ms) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
      listOf(input.id, input.text, p.normalized, m?.ref?.level?.name, m?.ref?.group, m?.ref?.id, m?.provenance?.key, m?.atEpochMs),
    )
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

  private fun exec(sql: String, args: List<Any?>) {
    db.prepare(sql).use { bind(it, args); it.step() }
  }

  private fun <T> query(sql: String, args: List<Any?>, row: (SQLiteStatement) -> T): List<T> =
    db.prepare(sql).use { st ->
      bind(st, args)
      buildList { while (st.step()) add(row(st)) }
    }

  private fun bind(st: SQLiteStatement, args: List<Any?>) = args.forEachIndexed { i, a ->
    when (a) {
      null -> st.bindNull(i + 1)
      is Long -> st.bindLong(i + 1, a)
      else -> st.bindText(i + 1, a as String)
    }
  }
}
