package com.bbpillow.haku.memory.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.Provenance

/** One embedded memory line. [memory].ref.key is the row id; [model] = which embedder made [vector]. */
class VectorRecord(val memory: IndexedMemory, val model: String, val vector: FloatArray)

/**
 * Embeddings of memory lines (ชิ้น ค semantic tier). Derived personal data like the FTS index: same DB, deleted with the
 * line (IndexedMemoryRepository), rebuildable from the files (thinking/context/VectorIndexer).
 */
interface VectorRepository {
  /** Insert or replace by ref key. */
  fun upsert(records: List<VectorRecord>)
  /** Ref keys that have a vector from [model]. */
  fun ids(model: String): Set<String>
  fun all(model: String): List<VectorRecord>
  fun remove(id: String): Boolean
  fun removeAll(ids: Collection<String>): Int
  fun removeGroup(level: MemoryLevel, group: String): Int
  /** Vectors from any other model are useless (different space): drop them. */
  fun retainModel(model: String): Int
  fun clear()
  fun count(model: String? = null): Int
}

/** All vector SQL. float32 little-endian blob per row. Not thread-safe: callers serialize access (the DB thread). */
class VectorStore(private val db: SQLiteConnection) : VectorRepository {
  init {
    db.execSQL("PRAGMA secure_delete=ON") // a forgotten line's vector is zeroed, not left in free pages
    // ponytail: derived data like the FTS index; a schema change means drop the table and re-embed from the files.
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS vectors(id TEXT PRIMARY KEY, level TEXT NOT NULL, grp TEXT NOT NULL, mid TEXT NOT NULL, " +
        "provenance TEXT NOT NULL, at_ms INTEGER NOT NULL, model TEXT NOT NULL, vec BLOB NOT NULL)",
    )
  }

  override fun upsert(records: List<VectorRecord>) = inTransaction {
    for (r in records) {
      val ref = r.memory.ref
      db.prepare("INSERT OR REPLACE INTO vectors(id, level, grp, mid, provenance, at_ms, model, vec) VALUES(?, ?, ?, ?, ?, ?, ?, ?)").use {
        it.bindText(1, ref.key)
        it.bindText(2, ref.level.name)
        it.bindText(3, ref.group)
        it.bindText(4, ref.id)
        it.bindText(5, r.memory.provenance.key)
        it.bindLong(6, r.memory.atEpochMs)
        it.bindText(7, r.model)
        it.bindBlob(8, toBlob(r.vector))
        it.step()
      }
    }
  }

  override fun ids(model: String): Set<String> = query("SELECT id FROM vectors WHERE model = ?", listOf(model)) { it.getText(0) }.toSet()

  override fun all(model: String): List<VectorRecord> =
    query("SELECT level, grp, mid, provenance, at_ms, model, vec FROM vectors WHERE model = ?", listOf(model)) { st ->
      val prov = Provenance.fromKey(st.getText(3)) ?: Provenance.EXTERNAL // unknown stamp = least trusted
      VectorRecord(IndexedMemory(MemoryRef(MemoryLevel.valueOf(st.getText(0)), st.getText(1), st.getText(2)), prov, st.getLong(4)), st.getText(5), fromBlob(st.getBlob(6)))
    }

  override fun remove(id: String): Boolean = change("DELETE FROM vectors WHERE id = ?", listOf(id)) > 0

  override fun removeAll(ids: Collection<String>): Int = inTransaction { ids.sumOf { change("DELETE FROM vectors WHERE id = ?", listOf(it)) } }

  override fun removeGroup(level: MemoryLevel, group: String): Int = change("DELETE FROM vectors WHERE level = ? AND grp = ?", listOf(level.name, group))

  override fun retainModel(model: String): Int = change("DELETE FROM vectors WHERE model <> ?", listOf(model))

  override fun clear() {
    change("DELETE FROM vectors", emptyList())
  }

  override fun count(model: String?): Int =
    if (model == null) query("SELECT count(*) FROM vectors", emptyList()) { it.getLong(0).toInt() }.first()
    else query("SELECT count(*) FROM vectors WHERE model = ?", listOf(model)) { it.getLong(0).toInt() }.first()

  private fun change(sql: String, args: List<String>): Int {
    db.prepare(sql).use { st -> args.forEachIndexed { i, a -> st.bindText(i + 1, a) }; st.step() }
    return db.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
  }

  private fun <T> query(sql: String, args: List<String>, row: (SQLiteStatement) -> T): List<T> =
    db.prepare(sql).use { st ->
      args.forEachIndexed { i, a -> st.bindText(i + 1, a) }
      buildList { while (st.step()) add(row(st)) }
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

  internal companion object {
    fun toBlob(v: FloatArray): ByteArray {
      val out = ByteArray(v.size * 4)
      v.forEachIndexed { i, f ->
        val bits = f.toRawBits()
        for (b in 0 until 4) out[i * 4 + b] = (bits ushr (8 * b)).toByte()
      }
      return out
    }

    fun fromBlob(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 4) { i ->
      var bits = 0
      for (b in 0 until 4) bits = bits or ((bytes[i * 4 + b].toInt() and 0xFF) shl (8 * b))
      Float.fromBits(bits)
    }
  }
}
