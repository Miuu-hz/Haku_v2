package com.bbpillow.haku.memory

/** What the rest of the app uses. Long-term levels (core, standing) never expire; only these delete calls remove them. */
interface MemoryRepository {
  // short-term
  fun appendDaily(input: DailyEventInput): MemoryRecord
  fun readDay(day: String): List<MemoryRecord>
  fun deleteDay(day: String): Boolean

  /** Remove one daily record (user delete from the timeline). [day] is the file's UTC day, see [MemoryFormat.dayOf]. */
  fun deleteDaily(day: String, id: String): Boolean

  /** UTC days that have a daily file, ascending. */
  fun listDays(): List<String>

  /** Look one record up by address (search hit -> file record). */
  fun find(ref: MemoryRef): MemoryRecord?

  /**
   * The only path that copies a record into long-term memory. Provenance is taken from the source record,
   * never from the caller; EXTERNAL/SYSTEM sources throw [ProvenanceNotAllowedException] (P1.7).
   * Returns null when [from] does not exist. Source must be DAILY or INTENTION.
   */
  fun promote(from: MemoryRef, to: PromotionTarget): MemoryRecord?

  // curated core (long-term)
  fun writeCoreFact(input: CoreFactInput): MemoryRecord
  fun updateCoreFact(category: String, id: String, newText: String): MemoryRecord?
  fun deleteCoreFact(category: String, id: String): Boolean
  fun readCategory(category: String): List<MemoryRecord>
  fun listCategories(): List<CategorySummary>

  /** "Forget this whole category": the core file goes. False when it had no file. */
  fun deleteCategory(category: String): Boolean

  // standing instructions (long-term)
  fun addStanding(input: NoteInput): MemoryRecord
  fun readStanding(): List<MemoryRecord>
  fun deleteStanding(id: String): Boolean

  // pending intentions
  fun addIntention(input: NoteInput): MemoryRecord
  fun readIntentions(): List<MemoryRecord>
  fun deleteIntention(id: String): Boolean

  // dream report
  fun writeDreamReport(day: String, text: String)
  fun readDreamReport(day: String): String?

  /** UTC days that have a dream report, ascending (export needs them). */
  fun listDreamDays(): List<String>

  /** "Forget everything": daily, core, standing, intentions and dream files all go. */
  fun deleteAll()
}
