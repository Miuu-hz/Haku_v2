package com.bbpillow.haku.capture.diary

import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceVisitRecord
import com.bbpillow.haku.memory.Provenance

/** Ending particle the assistant uses (D15). Asked once at onboarding, then enforced on every line. */
enum class EndingParticle(val text: String) {
  NONE(""), KRAP("ครับ"), KA("ค่ะ");

  /** Thai typography: the particle joins a Thai word ("นาทีค่ะ") but is spaced after a digit, quote or Latin ("14:30 ค่ะ"). */
  fun appendTo(body: String): String {
    if (this == NONE) return body
    val form = formFor(body)
    val trimmed = body.trimEnd()
    val base = when {
      this == KA && trimmed.endsWith("ค่ะ") -> trimmed.removeSuffix("ค่ะ")
      this == KA && trimmed.endsWith("คะ") -> trimmed.removeSuffix("คะ")
      this == KRAP && trimmed.endsWith("ครับ") -> trimmed.removeSuffix("ครับ")
      else -> trimmed
    }.trimEnd()
    val last = base.lastOrNull() ?: return form
    return if (last in '฀'..'๿') "$base$form" else "$base $form"
  }

  fun formFor(body: String): String {
    if (this != KA) return text
    // ponytail: question detection via trailing '?' and ending word list; full question detection needs NLP
    val trimmed = body.trimEnd()
    val hasQuestionMark = trimmed.endsWith('?') || trimmed.endsWith('？')
    val clean = trimmed.trimEnd { it.isWhitespace() || it == '.' || it == '!' || it == '…' || it == '?' || it == '？' || it == '\u200B' }
    val withoutParticle = when {
      clean.endsWith("ครับ") -> clean.removeSuffix("ครับ")
      clean.endsWith("ค่ะ") -> clean.removeSuffix("ค่ะ")
      clean.endsWith("คะ") -> clean.removeSuffix("คะ")
      else -> clean
    }.trimEnd()
    if (hasQuestionMark) return "คะ"
    if (SOFTENERS.any { withoutParticle.endsWith(it) } && NOT_SOFTENER.none { withoutParticle.endsWith(it) }) return "คะ"
    val targetBody = if (withoutParticle.endsWith("มา")) withoutParticle.removeSuffix("มา").trimEnd() else withoutParticle
    if (YES_NO.any { withoutParticle.endsWith(it) }) return "คะ"
    // "ไม่ได้ไปไหน", "ไม่มีอะไร": a question word after ไม่ is an indefinite in a statement
    if ("ไม่" in targetBody.takeLast(NEGATION_REACH)) return "ค่ะ"
    if (QUESTION_WORDS.any { withoutParticle.endsWith(it) || targetBody.endsWith(it) }) return "คะ"
    return "ค่ะ"
  }

  /** Strips this particle's suffix(es) from [text], handling both forms for [KA]. */
  fun stripFrom(text: String): String = when (this) {
    NONE -> text
    KRAP -> text.removeSuffix("ครับ")
    KA -> text.removeSuffix("ค่ะ").removeSuffix("คะ")
  }

  companion object {
    // ponytail: suffix match (Thai has no word breaks); words that only end like a softener go in NOT_SOFTENER. Grow on misses.
    private val SOFTENERS = listOf("นะ", "น้า", "จ้ะ", "เนอะ", "สิ", "ล่ะ")
    private val NOT_SOFTENER = listOf("หน้า", "ชนะ", "ทัศนะ")
    /** Always a question, even after ไม่ ("ไม่ไปไหม"). */
    private val YES_NO = listOf("ไหม", "มั้ย", "หรือ", "หรือเปล่า", "หรือยัง", "ใช่ไหม")
    private val QUESTION_WORDS = listOf("ไหน", "อะไร", "ใคร", "เมื่อไหร่", "เมื่อไร", "ยังไง", "อย่างไร", "ทำไม", "กี่", "บ้าง")
    private const val NEGATION_REACH = 12
  }
}

/** How the assistant calls the user (D15): "คุณ", the nickname, or nothing. */
enum class AddressForm { NONE, KHUN, NICKNAME }

/** The user's speech-style choice from onboarding (B4). [nickname] is used only with [AddressForm.NICKNAME]. */
data class DiaryStyle(
  val particle: EndingParticle = EndingParticle.NONE,
  val address: AddressForm = AddressForm.NONE,
  val nickname: String = "",
) {
  /** "คุณ", the nickname, or null when the user chose not to be addressed (or left the nickname blank). */
  val addressName: String?
    get() = when (address) {
      AddressForm.NONE -> null
      AddressForm.KHUN -> "คุณ"
      AddressForm.NICKNAME -> nickname.trim().ifEmpty { null }
    }
}

/** Tunables. Starting values, adjust with real weeks of data (as PlaceThresholds). */
object DiaryThresholds {
  // Shorter stays never reach the diary: PlaceThresholds.MIN_DWELL_MS (15 min) already drops them upstream.
  // A gap between two stays counts as a trip when 10..90 min; shorter is a walk, longer hides unrecorded stops.
  const val MIN_TRAVEL_MS = 10 * 60_000L
  const val MAX_TRAVEL_MS = 90 * 60_000L
  const val LONG_STAY_MS = 2 * 3_600_000L
  const val MAX_LINES = 12

  /** P3.2: after this many user deletions of one line type the diary stops writing that type. */
  const val SUPPRESS_AFTER_DELETES = 3
}

/** How a place is shown. Only user-confirmed places carry a label/category; anything else is neutral. */
data class PlaceRef(val label: String? = null, val category: PlaceCategory = PlaceCategory.OTHER, val placeId: String? = null)

enum class DiaryLineKind { DEPART, ARRIVE, CALENDAR }

/** One thing worth writing down, before wording. */
sealed interface DiaryEpisode {
  val timeUtcMs: Long
  val sourceEventIds: List<String>
  val kind: DiaryLineKind
  val priority: Int

  /** Line-type key for delete-three-times suppression, see [LineTypeKey]. */
  val typeKey: String

  data class Departure(override val timeUtcMs: Long, val from: PlaceRef, override val sourceEventIds: List<String>) : DiaryEpisode {
    override val kind get() = DiaryLineKind.DEPART
    override val typeKey get() = LineTypeKey.place(kind, from)
    override val priority get() = 1
  }

  /** [travelMs] = the trip that led here (null when none); [stayMs] = null when the stay outlasts the day. */
  data class Arrival(
    override val timeUtcMs: Long,
    val at: PlaceRef,
    val travelMs: Long?,
    val stayMs: Long?,
    override val sourceEventIds: List<String>,
  ) : DiaryEpisode {
    override val kind get() = DiaryLineKind.ARRIVE
    override val typeKey get() = LineTypeKey.place(kind, at)
    override val priority get() = if ((stayMs ?: 0L) >= DiaryThresholds.LONG_STAY_MS) 3 else 2
  }

  data class Calendar(
    override val timeUtcMs: Long,
    val title: String,
    val endUtcMs: Long?,
    override val sourceEventIds: List<String>,
  ) : DiaryEpisode {
    override val kind get() = DiaryLineKind.CALENDAR
    override val typeKey get() = LineTypeKey.calendar(title)
    override val priority get() = 4
  }
}

/** Everything the builder needs for one local day; events may extend past the day (neighbouring stays). */
data class DiaryBuildInput(
  val dayStartUtcMs: Long,
  val dayEndUtcMs: Long,
  val events: List<CaptureEvent>,
  val places: List<PlaceRecord>,
  val visits: List<PlaceVisitRecord>,
)

/**
 * One diary line as the timeline (P3.2) and the evening summary (P2.7) see it.
 * [id] derives from the source events only, so it survives re-runs, rewording and style changes,
 * and a user delete can be matched to it. [timeUtcMs] is UTC (D17); [text] already starts with local HH:MM.
 */
data class DiaryLineRecord(
  val id: String,
  val day: String,
  val kind: DiaryLineKind,
  val timeUtcMs: Long,
  val text: String,
  val sourceEventIds: List<String>,
  val provenance: Provenance = Provenance.INFERRED_FROM_USER_DATA,
  val typeKey: String = "",
)

/** [header] is the Buddhist Era display header; it is never stored. */
data class DiaryDayOutput(val day: String, val header: String, val lines: List<DiaryLineRecord>)

data class DiaryComposeInput(val day: String, val tzOffsetMinutes: Int, val style: DiaryStyle = DiaryStyle())

/** [written] = lines newly added to the daily log; [existing] already there; [suppressed] deleted by the user earlier. */
data class DiaryComposeOutput(
  val header: String,
  val written: List<DiaryLineRecord>,
  val existing: Int,
  val suppressed: Int,
)

/** What the ledger remembers about one line. A deleted line stays in the ledger forever (tombstone). */
data class DiaryLedgerEntry(val lineId: String, val memoryDay: String, val memoryRecordId: String, val deleted: Boolean)

/**
 * What counts as "the same type of line" for P3.2 suppression. Coarse enough that three deletions happen within days
 * (a whole kind would silence every arrival because of one unwanted gym line), fine enough to be what the user meant:
 *  - arrival/departure: kind + place id ("stop telling me I arrived at the store"; home and work stay untouched)
 *  - calendar: normalized title (case and spacing ignored), so a recurring "standup" is one type
 * Unconfirmed places still key by id, so the user can silence a place without ever naming it.
 */
object LineTypeKey {
  fun place(kind: DiaryLineKind, ref: PlaceRef): String = "${kind.name}:${ref.placeId.orEmpty()}"

  fun calendar(title: String): String =
    "${DiaryLineKind.CALENDAR.name}:" + title.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
}

/** A line type the diary has stopped writing, for the future settings screen. [deletes] = strikes counted. */
data class SuppressedLineType(val typeKey: String, val deletes: Int)
