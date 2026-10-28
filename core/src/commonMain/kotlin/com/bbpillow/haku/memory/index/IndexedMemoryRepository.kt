package com.bbpillow.haku.memory.index

import com.bbpillow.haku.memory.CategorySummary
import com.bbpillow.haku.memory.CoreFactInput
import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryLevel
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.MemoryRef
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.NoteInput
import com.bbpillow.haku.memory.PromotionTarget

internal fun MemoryRecord.toIndexInput(ref: MemoryRef) =
  IndexRecordInput(ref.key, text, memory = IndexedMemory(ref, provenance, atEpochMs))

/**
 * Keeps the search index in step with memory: every write/update/delete goes to [delegate] (the files, source of truth)
 * first, then to [index]. If the index step throws, the file change already happened; call [MemoryIndexer.rebuild].
 * [vectors] (semantic tier) follow every delete the same way, so a forgotten line's vector never outlives it.
 */
class IndexedMemoryRepository(
  private val delegate: MemoryRepository,
  private val index: SearchRepository,
  private val vectors: VectorRepository? = null,
) : MemoryRepository by delegate {
  private fun drop(ref: MemoryRef) {
    index.remove(ref.key)
    vectors?.remove(ref.key)
  }

  private fun dropGroup(level: MemoryLevel, group: String) {
    index.removeGroup(level, group)
    vectors?.removeGroup(level, group)
  }

  private fun MemoryRecord.indexAs(level: MemoryLevel, group: String): MemoryRecord =
    also { index.index(toIndexInput(MemoryRef(level, group, id))) }

  override fun appendDaily(input: DailyEventInput): MemoryRecord =
    delegate.appendDaily(input).let { it.indexAs(MemoryLevel.DAILY, MemoryFormat.dayOf(it.atEpochMs)) }

  override fun deleteDay(day: String): Boolean = delegate.deleteDay(day).also { dropGroup(MemoryLevel.DAILY, day) }

  override fun deleteDaily(day: String, id: String): Boolean =
    delegate.deleteDaily(day, id).also { if (it) drop(MemoryRef(MemoryLevel.DAILY, day, id)) }

  override fun writeCoreFact(input: CoreFactInput): MemoryRecord = delegate.writeCoreFact(input).indexAs(MemoryLevel.CORE, input.category)

  override fun updateCoreFact(category: String, id: String, newText: String): MemoryRecord? =
    delegate.updateCoreFact(category, id, newText)?.indexAs(MemoryLevel.CORE, category)
      ?.also { vectors?.remove(MemoryRef(MemoryLevel.CORE, category, id).key) } // text changed: the old vector is wrong

  override fun deleteCoreFact(category: String, id: String): Boolean =
    delegate.deleteCoreFact(category, id).also { if (it) drop(MemoryRef(MemoryLevel.CORE, category, id)) }

  override fun deleteCategory(category: String): Boolean =
    delegate.deleteCategory(category).also { dropGroup(MemoryLevel.CORE, category) }

  override fun deleteAll() {
    delegate.deleteAll()
    vectors?.clear()
    index.clear()
    index.compact() // last database step of "forget everything": erase free pages (vectors share the file)
  }

  override fun addStanding(input: NoteInput): MemoryRecord = delegate.addStanding(input).indexAs(MemoryLevel.STANDING, "")

  override fun deleteStanding(id: String): Boolean =
    delegate.deleteStanding(id).also { if (it) drop(MemoryRef(MemoryLevel.STANDING, "", id)) }

  override fun addIntention(input: NoteInput): MemoryRecord = delegate.addIntention(input).indexAs(MemoryLevel.INTENTION, "")

  override fun updateIntention(id: String, newText: String): MemoryRecord? =
    delegate.updateIntention(id, newText)?.indexAs(MemoryLevel.INTENTION, "")
      ?.also { vectors?.remove(MemoryRef(MemoryLevel.INTENTION, "", id).key) }

  override fun deleteIntention(id: String): Boolean =
    delegate.deleteIntention(id).also { if (it) drop(MemoryRef(MemoryLevel.INTENTION, "", id)) }

  override fun promote(from: MemoryRef, to: PromotionTarget): MemoryRecord? =
    delegate.promote(from, to)?.let {
      when (to) {
        is PromotionTarget.Core -> it.indexAs(MemoryLevel.CORE, to.category)
        PromotionTarget.Standing -> it.indexAs(MemoryLevel.STANDING, "")
      }
    }
}

/** Result of a rebuild. [quarantined] = long-term records with non-permanent provenance found in files; not indexed. */
data class RebuildReport(val indexed: Int, val quarantined: List<MemoryRef>)

/** Rebuilds the index from the memory files. Files win: whatever the index held is discarded. */
class MemoryIndexer(private val memory: MemoryRepository, private val index: SearchRepository) {
  fun rebuild(): RebuildReport {
    index.clear()
    val inputs = mutableListOf<IndexRecordInput>()
    val quarantined = mutableListOf<MemoryRef>()
    for (day in memory.listDays()) collect(MemoryLevel.DAILY, day, inputs, quarantined)
    for (c in memory.listCategories()) collect(MemoryLevel.CORE, c.slug, inputs, quarantined)
    collect(MemoryLevel.STANDING, "", inputs, quarantined)
    collect(MemoryLevel.INTENTION, "", inputs, quarantined)
    index.indexAll(inputs)
    return RebuildReport(inputs.size, quarantined)
  }

  /** One file changed or vanished (the watcher's job): drop its rows, re-read the file. */
  fun resync(level: MemoryLevel, group: String): RebuildReport {
    index.removeGroup(level, group)
    val inputs = mutableListOf<IndexRecordInput>()
    val quarantined = mutableListOf<MemoryRef>()
    collect(level, group, inputs, quarantined)
    index.indexAll(inputs)
    return RebuildReport(inputs.size, quarantined)
  }

  private fun collect(level: MemoryLevel, group: String, out: MutableList<IndexRecordInput>, quarantined: MutableList<MemoryRef>) {
    val records = when (level) {
      MemoryLevel.DAILY -> memory.readDay(group)
      MemoryLevel.CORE -> memory.readCategory(group)
      MemoryLevel.STANDING -> memory.readStanding()
      MemoryLevel.INTENTION -> memory.readIntentions()
    }
    val longTerm = level == MemoryLevel.CORE || level == MemoryLevel.STANDING
    for (r in records) {
      val ref = MemoryRef(level, group, r.id)
      if (longTerm && !r.provenance.canBePermanent) quarantined += ref else out += r.toIndexInput(ref)
    }
  }
}
