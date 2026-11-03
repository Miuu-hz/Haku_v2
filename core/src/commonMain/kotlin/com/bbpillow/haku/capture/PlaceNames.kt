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
 * name from the calendar or address, else "สถานที่ A", "สถานที่ B" … by first visit. Pure; the worker does the lookups and the writes.
 */
object PlaceNames {
  private const val FALLBACK_PREFIX = "สถานที่ "
  private const val MAX_LEN = 32
  private val junk = setOf("Unnamed Road", "ถนนไม่มีชื่อ")
  private val fallback = Regex("""^สถานที่ [A-Z]+$""")

  // ponytail: substring match; bare "วัด" left out (วัดความดัน, วัดผล). Add nouns when a real title misses.
  val PLACE_NOUNS = listOf(
    "ศาลากลาง", "โรงพยาบาล", "รพ.", "คลินิก", "มหาวิทยาลัย", "มข.",
    "โรงเรียน", "สนามบิน", "ห้าง", "เซ็นทรัล", "ตลาด",
    "ธนาคาร", "สำนักงาน", "ที่ว่าการ", "อำเภอ", "เทศบาล", "ร้าน",
    "คาเฟ่", "โรงแรม", "ฟิตเนส", "ยิม", "สถานี",
  )

  /** Short label: a real feature name (not a house number or plus code), else road, else sub-district, district. */
  fun label(a: GeocodedAddressInput): String? {
    val feature = a.featureName?.takeUnless { it == a.houseNumber || it == a.postalCode }
    return listOf(feature, a.thoroughfare, a.subLocality, a.locality, a.subAdminArea)
      .mapNotNull { it?.trim() }
      .firstOrNull { it.isNotEmpty() && it.length <= MAX_LEN && it !in junk && '+' !in it && it.any(Char::isLetter) }
  }

  fun isFallback(name: String): Boolean = fallback.matches(name)

  /** Finds a place noun in [title] from [PLACE_NOUNS] keeping any following proper name until the next space. */
  fun extractTitleNoun(title: String): String? {
    val match = PLACE_NOUNS
      .mapNotNull { noun ->
        val idx = title.indexOf(noun)
        if (idx >= 0) idx to noun else null
      }
      .minWithOrNull(compareBy({ it.first }, { -it.second.length })) ?: return null
    val start = match.first
    val end = title.indexOf(' ', start).let { if (it == -1) title.length else it }
    val candidate = title.substring(start, end).trim()
    return candidate.takeIf {
      it.isNotEmpty() && it.length <= MAX_LEN && it !in junk && '+' !in it && it.any(Char::isLetter)
    }
  }

  /**
   * Candidate name per event: location if non-blank, <= 32 chars and not junk/plus-code, else title noun.
   * All-day, canceled and declined events are ignored.
   */
  fun candidateFromEvent(event: CalendarRowInput): String? {
    if (event.allDay || event.canceled || event.declined) return null
    val loc = event.location?.trim()?.takeIf {
      it.isNotEmpty() && it.length <= MAX_LEN && it !in junk && '+' !in it && it.any(Char::isLetter)
    }
    if (loc != null) return loc
    val title = event.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return extractTitleNoun(title)
  }

  /** Stay overlaps a non-all-day event by >= 50% of the event or >= 30 min. */
  fun overlaps(stayStartUtcMs: Long, stayEndUtcMs: Long, event: CalendarRowInput): Boolean {
    if (event.allDay || event.canceled || event.declined) return false
    val eventDuration = event.endUtcMs - event.beginUtcMs
    if (eventDuration <= 0L) return false
    val overlap = minOf(stayEndUtcMs, event.endUtcMs) - maxOf(stayStartUtcMs, event.beginUtcMs)
    if (overlap <= 0L) return false
    return (overlap * 2 >= eventDuration) || (overlap >= 30 * 60_000L)
  }

  /** Resolves candidates: single candidate or majority wins; tie returns null (keep geocoder). */
  fun resolveCandidate(candidates: List<String>): String? {
    if (candidates.isEmpty()) return null
    val counts = candidates.groupingBy { it }.eachCount()
    val max = counts.values.maxOrNull() ?: return null
    val top = counts.filterValues { it == max }.keys
    return if (top.size == 1) top.first() else null
  }

  /** Candidate name for a place across its stays and overlapping calendar events. */
  fun calendarCandidate(stays: List<Pair<Long, Long>>, events: List<CalendarRowInput>): String? {
    if (stays.isEmpty() || events.isEmpty()) return null
    val candidates = mutableListOf<String>()
    for (event in events) {
      if (stays.any { (start, end) -> overlaps(start, end, event) }) {
        val candidate = candidateFromEvent(event)
        if (candidate != null) candidates += candidate
      }
    }
    return resolveCandidate(candidates)
  }

  /**
   * Calendar names to store for this run. [candidates]: place id -> calendar candidate name.
   * Confirmed places and places whose existing autoName already matches this base name are skipped.
   * Can upgrade an existing geocoder or fallback name.
   */
  fun assignCalendar(places: List<PlaceRecord>, candidates: Map<String, String>): Map<String, String> {
    val used = places.mapNotNull { it.autoName ?: it.label }.toMutableSet()
    val out = LinkedHashMap<String, String>()
    for (p in places) {
      if (p.confirmed || p.id !in candidates) continue
      val base = candidates[p.id] ?: continue
      if (p.autoName != null && (p.autoName == base || p.autoName!!.startsWith("$base แห่งที่ "))) continue
      val currentName = p.autoName
      val availableUsed = if (currentName != null) used - currentName else used
      val name = distinct(base, availableUsed)
      used += name
      out[p.id] = name
    }
    return out
  }

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
