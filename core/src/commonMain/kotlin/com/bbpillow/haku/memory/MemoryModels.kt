package com.bbpillow.haku.memory

/** Where a record came from (P1.7 stamps). Only user-sourced records may become permanent memory. */
enum class Provenance(val key: String, val canBePermanent: Boolean) {
  USER_TYPED("user_typed", true),
  INFERRED_FROM_USER_DATA("inferred", true),
  EXTERNAL("external", false),
  SYSTEM("system", false);

  companion object {
    fun fromKey(key: String): Provenance? = entries.firstOrNull { it.key == key }
  }
}

/** One memory entry. [text] is the user's original text, stored verbatim. Time is UTC epoch ms (D17). */
data class MemoryRecord(
  val id: String,
  val text: String,
  val provenance: Provenance,
  val atEpochMs: Long,
)

data class DailyEventInput(val text: String, val provenance: Provenance, val atEpochMs: Long)

data class CoreFactInput(
  val category: String,
  val text: String,
  val provenance: Provenance,
  val atEpochMs: Long,
)

/** Standing instruction or pending intention. */
data class NoteInput(val text: String, val provenance: Provenance, val atEpochMs: Long)

/** What a context assembler sees for a category without reading the whole file. */
data class CategorySummary(val slug: String, val description: String, val factCount: Int)

object CoreCategories {
  const val YOU = "you"
  const val TOPICS = "topics"
  const val AREAS = "areas"
  const val PROJECTS = "projects"

  val defaults: Map<String, String> = linkedMapOf(
    YOU to "The user: preferences and profile",
    TOPICS to "Subjects the user cares about",
    AREAS to "Areas of life: work, health, family",
    PROJECTS to "Projects the user is running",
  )
}

class ProvenanceNotAllowedException(provenance: Provenance) :
  IllegalArgumentException("${provenance.key} records can never be written to permanent memory")
