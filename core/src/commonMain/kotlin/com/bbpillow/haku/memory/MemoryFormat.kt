package com.bbpillow.haku.memory

/** Parsed Markdown memory file: small `key: value` frontmatter plus records. */
data class MemoryFile(val meta: Map<String, String>, val records: List<MemoryRecord>)

/**
 * File shape:
 * ```
 * ---
 * description: ...
 * ---
 *
 * <!-- haku:record id=a1 provenance=user_typed at=2026-09-30T01:02:03.004Z -->
 * text exactly as the user wrote it
 * <!-- /haku:record -->
 * ```
 * Content outside frontmatter and record blocks is not preserved on rewrite.
 */
object MemoryFormat {
  private const val OPEN = "<!-- haku:record "
  private const val HEAD_END = " -->\n"
  private const val CLOSE = "\n<!-- /haku:record -->"
  private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.(\d{3})Z""")

  fun serialize(file: MemoryFile): String = buildString {
    append("---\n")
    for ((k, v) in file.meta) {
      require('\n' !in v && '\n' !in k) { "frontmatter must be one line" }
      append(k).append(": ").append(v).append('\n')
    }
    append("---\n")
    for (r in file.records) {
      append('\n').append(OPEN).append("id=").append(r.id)
        .append(" provenance=").append(r.provenance.key)
        .append(" at=").append(formatUtc(r.atEpochMs)).append(HEAD_END)
        .append(escape(r.text)).append(CLOSE).append('\n')
    }
  }

  fun parse(content: String): MemoryFile {
    var pos = 0
    val meta = linkedMapOf<String, String>()
    if (content.startsWith("---\n")) {
      pos = 4
      while (pos < content.length) {
        val eol = content.indexOf('\n', pos).let { if (it < 0) content.length else it }
        val line = content.substring(pos, eol)
        pos = minOf(eol + 1, content.length)
        if (line == "---") break
        val c = line.indexOf(": ")
        if (c > 0) meta[line.substring(0, c)] = line.substring(c + 2)
      }
    }
    val records = mutableListOf<MemoryRecord>()
    while (true) {
      val start = content.indexOf(OPEN, pos)
      if (start < 0) break
      val headEnd = content.indexOf(HEAD_END, start)
      if (headEnd < 0) break
      val bodyStart = headEnd + HEAD_END.length
      val close = content.indexOf(CLOSE, bodyStart)
      if (close < 0) break // ponytail: truncated tail ignored; atomic writes make this a hand-edit case only
      parseHeader(content.substring(start + OPEN.length, headEnd))?.let { (id, prov, at) ->
        records += MemoryRecord(id, unescape(content.substring(bodyStart, close)), prov, at)
      }
      pos = close + CLOSE.length
    }
    return MemoryFile(meta, records)
  }

  // Bijective: every `<!-- [/]haku:` + n zero-width spaces + `record` gains one ZWSP on write and loses one on read,
  // so user text can never spell a record marker and old files (no ZWSP variants) read unchanged.
  private val marker = Regex("<!-- (/?)haku:(\u200B*)record")
  private val escapedMarker = Regex("<!-- (/?)haku:(\u200B+)record")
  private fun escape(t: String) = marker.replace(t) { "<!-- ${it.groupValues[1]}haku:${it.groupValues[2]}\u200Brecord" }
  private fun unescape(t: String) = escapedMarker.replace(t) { "<!-- ${it.groupValues[1]}haku:${it.groupValues[2].drop(1)}record" }

  private fun parseHeader(h: String): Triple<String, Provenance, Long>? {
    val kv = h.split(' ').mapNotNull { p ->
      p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) }
    }.toMap()
    val prov = kv["provenance"]?.let(Provenance::fromKey) ?: return null
    val at = kv["at"]?.let(::parseUtc) ?: return null
    return Triple(kv["id"] ?: return null, prov, at)
  }

  /** `yyyy-MM-dd` (UTC) of an instant; names the daily file. */
  fun dayOf(epochMs: Long): String = formatUtc(epochMs).substring(0, 10)

  // Civil-date conversion (Hinnant), pure Kotlin so core stays platform-free.
  fun formatUtc(epochMs: Long): String {
    val days = epochMs.floorDiv(DAY_MS)
    val msOfDay = epochMs.mod(DAY_MS)
    val z = days + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    fun p(n: Long, w: Int) = n.toString().padStart(w, '0')
    return "${p(y, 4)}-${p(m, 2)}-${p(d, 2)}T${p(msOfDay / 3_600_000, 2)}:${p(msOfDay / 60_000 % 60, 2)}:" +
      "${p(msOfDay / 1000 % 60, 2)}.${p(msOfDay % 1000, 3)}Z"
  }

  fun parseUtc(s: String): Long? {
    val g = ISO.matchEntire(s)?.groupValues?.drop(1)?.map { it.toLong() } ?: return null
    val (y0, mo, d) = g
    val y = if (mo <= 2) y0 - 1 else y0
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097 + doe - 719468
    return days * DAY_MS + g[3] * 3_600_000 + g[4] * 60_000 + g[5] * 1000 + g[6]
  }

  private const val DAY_MS = 86_400_000L
}
