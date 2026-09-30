package com.bbpillow.haku.memory

import kotlin.random.Random
import okio.FileSystem
import okio.Path

/**
 * All memory file IO. Layout under [root]:
 * ```
 * INSTRUCTIONS.md     standing instructions
 * INTENTIONS.md       pending intentions
 * core/<category>.md  curated core, one file per category, description in frontmatter
 * daily/<yyyy-MM-dd>.md
 * dreams/<yyyy-MM-dd>.md
 * ```
 * Writes go to a temp file then an atomic rename. Not thread-safe: callers serialize access.
 * ponytail: no write-collision check or file encryption yet (P1.3 leftovers), add when a second writer / D11 wiring exists.
 */
class MemoryStore(
  private val fs: FileSystem,
  private val root: Path,
  private val newId: () -> String = { Random.nextLong().toULong().toString(16) },
) : MemoryRepository {
  private val standingPath = root / "INSTRUCTIONS.md"
  private val intentionsPath = root / "INTENTIONS.md"
  private val slugRule = Regex("[a-z0-9][a-z0-9-]{0,39}")
  private val dayRule = Regex("""\d{4}-\d{2}-\d{2}""")

  // short-term
  override fun appendDaily(input: DailyEventInput): MemoryRecord =
    append(dailyPath(MemoryFormat.dayOf(input.atEpochMs)), mapOf("kind" to "daily"), input.text, input.provenance, input.atEpochMs)

  override fun readDay(day: String): List<MemoryRecord> = read(dailyPath(day)).records
  override fun deleteDay(day: String): Boolean = remove(dailyPath(day))
  override fun deleteDaily(day: String, id: String): Boolean = deleteRecord(dailyPath(day), id)

  override fun listDays(): List<String> =
    if (fs.exists(root / "daily")) fs.list(root / "daily").map { it.name }.filter { it.endsWith(".md") }
      .map { it.removeSuffix(".md") }.filter(dayRule::matches).sorted() else emptyList()

  override fun find(ref: MemoryRef): MemoryRecord? = when (ref.level) {
    MemoryLevel.DAILY -> readDay(ref.group)
    MemoryLevel.CORE -> readCategory(ref.group)
    MemoryLevel.STANDING -> readStanding()
    MemoryLevel.INTENTION -> readIntentions()
  }.firstOrNull { it.id == ref.id }

  override fun promote(from: MemoryRef, to: PromotionTarget): MemoryRecord? {
    require(from.level == MemoryLevel.DAILY || from.level == MemoryLevel.INTENTION) { "cannot promote from ${from.level}" }
    val src = find(from) ?: return null
    return when (to) {
      is PromotionTarget.Core -> writeCoreFact(CoreFactInput(to.category, src.text, src.provenance, src.atEpochMs))
      PromotionTarget.Standing -> addStanding(NoteInput(src.text, src.provenance, src.atEpochMs))
    }
  }

  // curated core
  override fun writeCoreFact(input: CoreFactInput): MemoryRecord {
    requirePermanent(input.provenance)
    return append(corePath(input.category), coreMeta(input.category), input.text, input.provenance, input.atEpochMs)
  }

  override fun updateCoreFact(category: String, id: String, newText: String): MemoryRecord? {
    val path = corePath(category)
    val file = read(path)
    val old = file.records.firstOrNull { it.id == id } ?: return null
    val new = old.copy(text = newText)
    write(path, file.copy(records = file.records.map { if (it.id == id) new else it }))
    return new
  }

  override fun deleteCoreFact(category: String, id: String): Boolean = deleteRecord(corePath(category), id)
  override fun readCategory(category: String): List<MemoryRecord> = read(corePath(category)).records

  override fun listCategories(): List<CategorySummary> {
    val onDisk = if (fs.exists(root / "core")) fs.list(root / "core").filter { it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") } else emptyList()
    return (CoreCategories.defaults.keys + onDisk.sorted()).distinct().map { slug ->
      val f = read(corePath(slug))
      CategorySummary(slug, f.meta["description"] ?: CoreCategories.defaults[slug].orEmpty(), f.records.size)
    }
  }

  override fun deleteCategory(category: String): Boolean = remove(corePath(category))

  // standing instructions
  override fun addStanding(input: NoteInput): MemoryRecord {
    requirePermanent(input.provenance)
    return append(standingPath, mapOf("kind" to "standing-instructions"), input.text, input.provenance, input.atEpochMs)
  }

  override fun readStanding(): List<MemoryRecord> = read(standingPath).records
  override fun deleteStanding(id: String): Boolean = deleteRecord(standingPath, id)

  // intentions
  override fun addIntention(input: NoteInput): MemoryRecord =
    append(intentionsPath, mapOf("kind" to "pending-intentions"), input.text, input.provenance, input.atEpochMs)

  override fun readIntentions(): List<MemoryRecord> = read(intentionsPath).records
  override fun deleteIntention(id: String): Boolean = deleteRecord(intentionsPath, id)

  // dream report: plain markdown text, one file per day
  override fun writeDreamReport(day: String, text: String) {
    val path = root / "dreams" / "${checkDay(day)}.md"
    fs.createDirectories(path.parent!!)
    atomicWrite(path, text)
  }

  override fun readDreamReport(day: String): String? {
    val path = root / "dreams" / "${checkDay(day)}.md"
    return if (fs.exists(path)) fs.read(path) { readUtf8() } else null
  }

  override fun listDreamDays(): List<String> =
    if (fs.exists(root / "dreams")) fs.list(root / "dreams").map { it.name }.filter { it.endsWith(".md") }
      .map { it.removeSuffix(".md") }.filter(dayRule::matches).sorted() else emptyList()

  override fun deleteAll() {
    listOf("daily", "core", "dreams").forEach { fs.deleteRecursively(root / it, mustExist = false) }
    listOf(standingPath, intentionsPath).forEach { fs.delete(it, mustExist = false) }
  }

  // helpers
  private fun requirePermanent(p: Provenance) {
    if (!p.canBePermanent) throw ProvenanceNotAllowedException(p)
  }

  private fun checkDay(day: String): String = day.also { require(dayRule.matches(it)) { "bad day: $it" } }
  private fun dailyPath(day: String) = root / "daily" / "${checkDay(day)}.md"
  private fun corePath(category: String): Path {
    require(slugRule.matches(category)) { "bad category: $category" }
    return root / "core" / "$category.md"
  }

  private fun coreMeta(category: String) = mapOf("description" to (CoreCategories.defaults[category] ?: category))

  private fun read(path: Path): MemoryFile =
    if (fs.exists(path)) MemoryFormat.parse(fs.read(path) { readUtf8() }) else MemoryFile(emptyMap(), emptyList())

  private fun write(path: Path, file: MemoryFile) {
    path.parent?.let { fs.createDirectories(it) }
    atomicWrite(path, MemoryFormat.serialize(file))
  }

  private fun atomicWrite(path: Path, content: String) {
    val tmp = path.parent!! / "${path.name}.tmp"
    fs.write(tmp) { writeUtf8(content) }
    fs.atomicMove(tmp, path)
  }

  private fun append(path: Path, meta: Map<String, String>, text: String, p: Provenance, at: Long): MemoryRecord {
    val record = MemoryRecord(newId(), text, p, at)
    val file = read(path)
    write(path, MemoryFile(file.meta.ifEmpty { meta }, file.records + record))
    return record
  }

  private fun deleteRecord(path: Path, id: String): Boolean {
    val file = read(path)
    if (file.records.none { it.id == id }) return false
    write(path, file.copy(records = file.records.filterNot { it.id == id }))
    return true
  }

  private fun remove(path: Path): Boolean = fs.exists(path).also { if (it) fs.delete(path) }
}
