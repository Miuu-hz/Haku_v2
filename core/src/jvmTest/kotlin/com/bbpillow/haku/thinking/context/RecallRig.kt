package com.bbpillow.haku.thinking.context

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.language.thaiSynonyms
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryStore
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.memory.index.DictionarySegmenter
import com.bbpillow.haku.memory.index.IndexedMemoryRepository
import com.bbpillow.haku.memory.index.MemoryIndexer
import com.bbpillow.haku.memory.index.SearchIndexStore
import com.bbpillow.haku.memory.index.VectorStore
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Files (fake fs) + real SQLite index + indexed repository + assembler, wired the way the app will. */
class RecallRig : AutoCloseable {
  val root = "/memory".toPath()
  val fs = FakeFileSystem()
  private var counter = 0
  val store = MemoryStore(fs, root) { "id${++counter}" }
  private val db: SQLiteConnection = BundledSQLiteDriver().open(":memory:")
  val pipeline = TextPipeline(DictionarySegmenter(VOCAB))
  val index = SearchIndexStore(db, pipeline, thaiSynonyms)
  val vectors = VectorStore(db)
  val memory = IndexedMemoryRepository(store, index, vectors)
  val indexer = MemoryIndexer(store, index)
  val assembler = ContextAssembler(memory, index, pipeline, thaiSynonyms)

  override fun close() = db.close()

  fun daily(text: String, iso: String, p: Provenance = Provenance.USER_TYPED) =
    memory.appendDaily(DailyEventInput(text, p, at(iso)))

  fun standing(text: String) = memory.addStanding(NoteInput(text, Provenance.USER_TYPED, NOW))

  fun ask(q: String, budget: Int = 2000) = assembler.assemble(AssembleInput(q, budget, NOW, OFFSET_MIN))

  companion object {
    fun at(iso: String) = MemoryFormat.parseUtc(iso)!!

    /** 2026-09-30 10:00 in Thailand (UTC+7). */
    val NOW = at("2026-09-30T03:00:00.000Z")
    const val OFFSET_MIN = 420

    val VOCAB = setOf(
      "เมื่อวาน", "วันนี้", "ไป", "ไหน", "มา", "ครั้ง", "ล่าสุด", "ที่", "ร้านกาแฟ", "ร้าน", "กาแฟ", "ประชุม", "กับ", "ทีม",
      "กิน", "ข้าว", "ออฟฟิศ", "สวนลุมพินี", "วิ่ง", "เมื่อไร", "คือ", "แพ้", "อาการ", "สุขภาพ", "ยา", "ถั่ว", "โครงการ",
      "ตอบ", "สั้น", "ๆ", "ภาษาไทย", "เสมอ", "ซื้อ", "ของ", "คาเฟ่", "หมอ", "นัด", "อะไร", "ฉัน",
    )
  }
}
