package com.bbpillow.haku.memory

/** What the rest of the app uses. Long-term levels (core, standing) never expire; only these delete calls remove them. */
interface MemoryRepository {
  // short-term
  fun appendDaily(input: DailyEventInput): MemoryRecord
  fun readDay(day: String): List<MemoryRecord>
  fun deleteDay(day: String): Boolean

  // curated core (long-term)
  fun writeCoreFact(input: CoreFactInput): MemoryRecord
  fun updateCoreFact(category: String, id: String, newText: String): MemoryRecord?
  fun deleteCoreFact(category: String, id: String): Boolean
  fun readCategory(category: String): List<MemoryRecord>
  fun listCategories(): List<CategorySummary>

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
}
