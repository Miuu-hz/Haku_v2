package com.bbpillow.haku.capture

/** The parts of one reverse-geocoded address Haku reads (android.location.Address, Thai locale). */
data class GeocodedAddressInput(
  val featureName: String? = null,
  val houseNumber: String? = null,
  val thoroughfare: String? = null,
  val subLocality: String? = null,
  val locality: String? = null,
  val subAdminArea: String? = null,
  val postalCode: String? = null,
)

/**
 * Haku's own names for places the user has not named (owner, 2026-11-03: "ชื่อสถานที่ลองหาดูหรือสุ่มๆไปเลย"): a short
 * name from the address, else "สถานที่ A", "สถานที่ B" … by first visit. Pure; the worker does the lookups and the writes.
 */
object PlaceNames {
  private const val FALLBACK_PREFIX = "สถานที่ "
  private const val MAX_LEN = 32
  private val junk = setOf("Unnamed Road", "ถนนไม่มีชื่อ")
  private val fallback = Regex("""^สถานที่ [A-Z]+$""")

  /** Short label: a real feature name (not a house number or plus code), else road, else sub-district, district. */
  fun label(a: GeocodedAddressInput): String? {
    val feature = a.featureName?.takeUnless { it == a.houseNumber || it == a.postalCode }
    return listOf(feature, a.thoroughfare, a.subLocality, a.locality, a.subAdminArea)
      .mapNotNull { it?.trim() }
      .firstOrNull { it.isNotEmpty() && it.length <= MAX_LEN && it !in junk && '+' !in it && it.any(Char::isLetter) }
  }

  fun isFallback(name: String): Boolean = fallback.matches(name)

  /**
   * Names to store for this run. [found]: place id -> geocoded label, or null = the lookup answered but had nothing usable
   * (that place gets the next letter). Ids missing from [found] were not looked up or failed: left for a later run.
   * A label another place already has gets " แห่งที่ 2" so every place reads as its own. Letters follow [places] order,
   * which is first-visit order because ids are "place-<first stay start ms>" and [PlaceRepository.list] sorts by id.
   */
  fun assign(places: List<PlaceRecord>, found: Map<String, String?>): Map<String, String> {
    val used = places.mapNotNull { it.autoName ?: it.label }.toMutableSet()
    var letters = places.count { it.autoName?.let(::isFallback) == true }
    val out = LinkedHashMap<String, String>()
    for (p in places) {
      if (p.autoName != null || p.id !in found) continue
      val base = found[p.id]
      val name = if (base == null) FALLBACK_PREFIX + letter(letters++) else distinct(base, used)
      used += name
      out[p.id] = name
    }
    return out
  }

  private fun distinct(base: String, used: Set<String>): String {
    if (base !in used) return base
    var n = 2
    while ("$base แห่งที่ $n" in used) n++
    return "$base แห่งที่ $n"
  }

  /** 0 -> A, 25 -> Z, 26 -> AA (spreadsheet columns). */
  private fun letter(index: Int): String {
    var i = index
    var s = ""
    do {
      s = ('A' + i % 26) + s
      i = i / 26 - 1
    } while (i >= 0)
    return s
  }
}
