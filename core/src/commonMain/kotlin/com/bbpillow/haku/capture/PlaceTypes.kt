package com.bbpillow.haku.capture

enum class PlaceCategory { HOME, WORK, OTHER }

/** One completed stay at a coordinate: a dwell-start paired with its dwell-end (see [PlaceRecognizer.pairDwells]). */
data class DwellRecord(val startUtcMs: Long, val endUtcMs: Long, val lat: Double, val lon: Double) {
  val durationMs: Long get() = endUtcMs - startUtcMs
}

/** A place in the user's library. [confirmed] flips once, when the user accepts or renames it; guesses skip confirmed places. */
data class PlaceRecord(
  val id: String,
  val lat: Double,
  val lon: Double,
  val label: String? = null,
  val category: PlaceCategory = PlaceCategory.OTHER,
  val confirmed: Boolean = false,
)

/** A stay (after merging jitter and dropping short stops) attached to a place. */
data class PlaceVisitRecord(val placeId: String, val startUtcMs: Long, val endUtcMs: Long)

/** A home/work guess the UI shows once for the user to confirm. */
/** [lat]/[lon] let a confirmation store the place even when the recognition run never persisted it. */
data class PlaceGuessOutput(
  val placeId: String,
  val category: PlaceCategory,
  val evidenceDays: Int,
  val lat: Double? = null,
  val lon: Double? = null,
)

data class PlaceRecognitionInput(
  val dwells: List<DwellRecord>,
  val knownPlaces: List<PlaceRecord> = emptyList(),
  /** Local offset from UTC (Thailand = 420). Needed only to read time-of-day patterns; storage stays UTC (D17). */
  val tzOffsetMinutes: Int = 0,
)

data class PlaceRecognitionOutput(
  val places: List<PlaceRecord>,
  val visits: List<PlaceVisitRecord>,
  val guesses: List<PlaceGuessOutput>,
)
