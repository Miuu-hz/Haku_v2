package com.bbpillow.haku.memory.index

/** Word-level full-text search over the memory records (D5). */
interface SearchRepository {
  /** Insert or replace by [IndexRecordInput.id]. */
  fun index(input: IndexRecordInput)

  /** Many records in one transaction (initial build / benchmark). */
  fun indexAll(inputs: List<IndexRecordInput>)

  fun remove(id: String): Boolean

  /** Drop every row of one file (level + group, e.g. a deleted day). Returns rows removed. */
  fun removeGroup(level: com.bbpillow.haku.memory.MemoryLevel, group: String): Int

  /** Empty the index (rebuild from files). */
  fun clear()
  fun count(): Int

  /** FTS on segmented words ranked by bm25; exact-substring fallback when that finds nothing. */
  fun search(query: SearchQueryInput): List<SearchHitOutput>
}
