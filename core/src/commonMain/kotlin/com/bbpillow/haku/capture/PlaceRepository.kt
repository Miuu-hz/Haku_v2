package com.bbpillow.haku.capture

/** The user's place library. Confirmation is stored once; later recognition runs never re-ask. */
interface PlaceRepository {
  fun list(): List<PlaceRecord>

  /** Insert new places; existing ids are left untouched (so a confirmed label is never overwritten by a re-run). */
  fun addNew(places: List<PlaceRecord>): Int

  /** User accepts a guess or names a place. Returns false when the id is unknown. */
  fun confirm(id: String, label: String, category: PlaceCategory): Boolean

  fun delete(id: String): Boolean

  /**
   * Haku's own name for a place, set once: false when the id is unknown or it already has one. A confirmed label still wins.
   * ponytail: default no-op for the read-only fakes; only PlaceStore persists it.
   */
  fun setAutoName(id: String, name: String, replace: Boolean = false): Boolean = false

  /** Forget everything: empty the place library (recognition rebuilds from events, which are forgotten too). */
  fun deleteAll()
}
