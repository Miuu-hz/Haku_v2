package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.capture.PlaceNames
import com.bbpillow.haku.capture.diary.DiaryTemplates
import com.bbpillow.haku.language.TextInput
import com.bbpillow.haku.language.TextPipeline
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.MemoryRecord
import com.bbpillow.haku.memory.Provenance

/** A daily record with the file day it lives in. */
internal data class DayRecord(val day: String, val record: MemoryRecord)

/** A recurring fact candidate: every record that mentions it, before any gate is applied. */
internal data class Theme(val key: String, val kind: ThemeKind, val subject: String, val records: List<DayRecord>) {
  val eligible: List<DayRecord> get() = records.filter { it.record.provenance.canBePermanent }
  val days: Int get() = records.map { it.day }.toSet().size
  val eligibleDays: Int get() = eligible.map { it.day }.toSet().size
}

/** Rule-based theme finder (stage 2): repeated place arrivals, repeated calendar titles, repeated user-typed notes. */
internal class DreamThemes(private val pipeline: TextPipeline, private val languageCode: String) {
  private val timePrefix = Regex("""^\d{1,2}[:.]\d{2}\s+""")
  private val arrival = Regex("""^ถึง(.+?)(?:\s+(?:ใช้เวลา|อยู่ราว).*)?$""")
  private val calendar = Regex("""^มีนัด “(.+?)”""")
  private val blanks = Regex("""\s+""")
  private val projectCues = listOf("โปรเจกต์", "โปรเจค", "project")
  private val youCues = listOf("ชอบ", "แพ้", "กินไม่ได้", "ฉันเป็น", "ผมเป็น")

  fun normalize(text: String): String = pipeline.process(TextInput(text, languageCode)).normalized.replace(blanks, " ").trim()

  fun find(records: List<DayRecord>): List<Theme> {
    val hits = records.mapNotNull { r -> classify(r.record)?.let { (kind, subject) -> Triple(kind, subject, r) } }
    return hits.groupBy { (kind, subject, _) -> "${kind.name}:${normalize(subject)}" }.map { (key, g) ->
      val latest = g.maxBy { it.third.record.atEpochMs }
      Theme(key, g.first().first, latest.second, g.map { it.third }.sortedBy { it.record.atEpochMs })
    }
  }

  private fun classify(r: MemoryRecord): Pair<ThemeKind, String>? {
    val body = r.text.trim().replace(timePrefix, "")
    arrival.find(body)?.groupValues?.get(1)?.trim()
      // ponytail: "สถานที่ A" means nothing once the user names the place, so it never becomes a long-term theme
      ?.takeIf { it.isNotEmpty() && it != DiaryTemplates.UNKNOWN_PLACE && it != DiaryTemplates.HOME && !PlaceNames.isFallback(it) }
      ?.let { return ThemeKind.PLACE to it }
    calendar.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return ThemeKind.CALENDAR to it }
    return if (r.provenance == Provenance.USER_TYPED && body.isNotEmpty()) ThemeKind.NOTE to body else null
  }

  fun template(t: Theme): String = when (t.kind) {
    ThemeKind.PLACE -> "ไป${t.subject}เป็นประจำ"
    ThemeKind.CALENDAR -> "มีนัด “${t.subject}” เป็นประจำ"
    ThemeKind.NOTE -> t.subject
  }

  /** Rule-based category choice. Standing instructions only ever come from the user's own command, never from Dream. */
  fun category(t: Theme): String = when {
    t.kind != ThemeKind.NOTE -> CoreCategories.AREAS
    projectCues.any { it in t.subject.lowercase() } -> CoreCategories.PROJECTS
    youCues.any { it in t.subject } -> CoreCategories.YOU
    else -> CoreCategories.TOPICS
  }

  /** Score in 0..1 from frequency, recency and the user-typed share; fixed rule weights, no model. */
  fun score(t: Theme, today: Long, cfg: DreamConfig, dayIndex: (String) -> Long): Double {
    if (t.eligible.isEmpty()) return 0.0
    val freq = minOf(1.0, t.eligibleDays / (2.0 * cfg.minDays + 4)) // saturates at 10 days with minDays=3
    val age = today - dayIndex(t.eligible.maxOf { it.day })
    val recency = (1.0 - age.toDouble() / cfg.lookbackDays).coerceIn(0.0, 1.0)
    val user = t.eligible.count { it.record.provenance == Provenance.USER_TYPED }.toDouble() / t.eligible.size
    return 0.5 * freq + 0.3 * recency + 0.2 * user
  }
}
