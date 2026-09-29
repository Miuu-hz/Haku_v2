package com.bbpillow.haku.capture

/** The user's place library. Confirmation is stored once; later recognition runs never re-ask. */
interface PlaceRepository {
  fun list(): List<PlaceRecord>

  /** Insert new places; existing ids are left untouched (so a confirmed label is never overwritten by a re-run). */
  fun addNew(places: List<PlaceRecord>): Int

  /** User accepts a guess or names a place. Returns false when the id is unknown. */
  fun confirm(id: String, label: String, category: PlaceCategory): Boolean

  fun delete(id: String): Boolean
}
