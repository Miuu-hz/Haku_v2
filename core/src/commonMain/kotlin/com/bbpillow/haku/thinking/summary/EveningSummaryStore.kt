package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.memory.Provenance
import okio.FileSystem
import okio.Path

/** One small file per local day under [root]: two header lines (source, provenance), a blank line, then the text. */
class EveningSummaryStore(private val fs: FileSystem, private val root: Path) : EveningSummaryRepository {
  override fun find(day: String): EveningSummaryRecord? {
    val path = pathOf(day)
    if (!fs.exists(path)) return null
    val raw = fs.read(path) { readUtf8() }
    val parts = raw.split("\n", limit = 3)
    if (parts.size < 3) return null
    val source = SummarySource.entries.firstOrNull { "source: ${it.name}" == parts[0] } ?: return null
    val provenance = Provenance.fromKey(parts[1].removePrefix("provenance: ")) ?: return null
    return EveningSummaryRecord(day, parts[2].removePrefix("\n"), source, provenance)
  }

  override fun save(record: EveningSummaryRecord) {
    val path = pathOf(record.day)
    fs.createDirectories(root)
    val tmp = root / "${record.day}.tmp"
    fs.write(tmp) { writeUtf8("source: ${record.source.name}\nprovenance: ${record.provenance.key}\n\n${record.text}") }
    fs.atomicMove(tmp, path)
  }

  override fun delete(day: String): Boolean = fs.exists(pathOf(day)).also { if (it) fs.delete(pathOf(day)) }

  override fun clear() {
    if (fs.exists(root)) fs.deleteRecursively(root)
  }

  private fun pathOf(day: String): Path {
    require(Regex("""\d{4}-\d{2}-\d{2}""").matches(day)) { "bad day: $day" }
    return root / "$day.md"
  }
}
