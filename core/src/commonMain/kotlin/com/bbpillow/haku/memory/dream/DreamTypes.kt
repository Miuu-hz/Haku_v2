package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.memory.MemoryLevel

/** Knobs of one night's run. Defaults are the P1.8 gate; tests and the nightly job may tighten them. */
data class DreamConfig(
  val minDays: Int = 3, // a fact must recur on at least this many distinct days, all permanent-eligible
  val lookbackDays: Int = 30,
  val maxRecords: Int = 2000, // work bound per run (charging window); newest days are read first
  val maxPromotionsPerRun: Int = 5,
  val maxCoreFacts: Int = 200, // size budget of the curated core
  val minScore: Double = 0.3,
  val maxTidyLossRatio: Double = 0.3, // tidy is cancelled if it would delete more than this share of scanned records
  val minReportDays: Int = 2, // themes seen on fewer days are noise, not worth a line in the report
  val languageCode: String = "th",
)

/** [day] is the night being dreamt, UTC yyyy-MM-dd (names the report file and anchors recency). */
data class DreamInput(val day: String)

data class TidiedRecord(val level: MemoryLevel, val day: String, val id: String, val text: String, val keptId: String, val exact: Boolean)

data class PromotedFact(
  val category: String,
  val factId: String,
  val text: String,
  val themeKey: String,
  val score: Double,
  val days: Int,
  val sourceIds: List<String>,
)

enum class HoldReason { TOO_FEW_DAYS, PROVENANCE, LOW_SCORE, FORGOTTEN, REJECTED, ALREADY_KNOWN, BUDGET, SOURCE_MISSING }

data class HeldBackTheme(val subject: String, val reason: HoldReason, val days: Int, val sourceIds: List<String>)

data class DreamOutput(
  val day: String,
  val alreadyRan: Boolean,
  val tidied: List<TidiedRecord>,
  val tidyCancelled: Boolean,
  val promoted: List<PromotedFact>,
  val heldBack: List<HeldBackTheme>,
  val truncated: Boolean,
  val report: String,
)

enum class ThemeKind { PLACE, CALENDAR, NOTE }

/** What a model is asked to phrase. [sourceTexts] are the records that support the fact. */
data class PhraseInput(val kind: ThemeKind, val subject: String, val days: Int, val sourceTexts: List<String>)

/** Optional model hook. Null or a rejected phrase falls back to the template; the model never decides promotion. */
fun interface FactPhraser {
  suspend fun phrase(input: PhraseInput): String?
}
