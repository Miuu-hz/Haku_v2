package com.bbpillow.haku.surface.onboarding

import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceGuessOutput
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import kotlin.math.round

/** Asset B1-B7, in order. [FIRST_PLACE] is only part of the flow when a home/work guess exists. */
enum class OnboardingStep(val skippable: Boolean) {
  WELCOME(false),
  PRIVACY(false),
  PERMISSIONS(true),
  STYLE(false), // D15: asked once, "ไม่ใช้" is one of the answers, so there is nothing to skip
  MODEL(true),
  LOCK(true),
  FIRST_PLACE(true),
}

/** Runtime permissions from docs/permissions.md. The Android side maps them to manifest names and asks. */
enum class PermissionKind { LOCATION, ACTIVITY, CALENDAR, BACKGROUND_LOCATION }

/** One explainer card (B3): why we ask, and what happens without it. Shown before the system dialog, every time. */
data class PermissionItem(val kind: PermissionKind, val title: String, val reason: String, val ifDenied: String)

/** Built-in on-device models (Google AI Edge Gallery 1_0_20 allowlist, pinned Hugging Face commits). */
enum class ModelChoice(
  val title: String,
  val fileName: String,
  val repo: String,
  val commit: String,
  val bytes: Long,
  val minRamGb: Int,
  val note: String,
) {
  E2B(
    title = "Gemma 4 E2B",
    fileName = "gemma-4-E2B-it.litertlm",
    repo = "litert-community/gemma-4-E2B-it-litert-lm",
    commit = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
    bytes = 2_588_147_712L,
    minRamGb = 8,
    note = "แนะนำ · เร็วและประหยัดแบต",
  ),
  E4B(
    title = "Gemma 4 E4B",
    fileName = "gemma-4-E4B-it.litertlm",
    repo = "litert-community/gemma-4-E4B-it-litert-lm",
    commit = "28299f30ee4d43294517a4ac93abd6163412f07f",
    bytes = 3_659_530_240L,
    minRamGb = 12,
    note = "ฉลาดขึ้น ใช้แรงเครื่องมากขึ้น",
  ),
  GEMMA3_1B(
    title = "Gemma 3 1B",
    fileName = "gemma3-1b-it-int4.litertlm",
    repo = "litert-community/Gemma3-1B-IT",
    commit = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
    bytes = 584_417_280L,
    minRamGb = 6,
    note = "เล็กและเร็ว · ภาษาไทยอ่อนกว่า",
  );

  val sizeGb: Double get() = round(bytes / 100_000_000.0) / 10.0
  val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$fileName?download=true"
}

/**
 * Everything the onboarding pager draws. [downloadProgress] null = not started, 1f = done; the download itself runs
 * outside common code and reports back through [OnboardingPresenter.downloadUpdate]. [placeAnswered] = the user
 * already said yes/no to [placeGuess].
 */
data class OnboardingUiState(
  val step: OnboardingStep,
  val steps: List<OnboardingStep>,
  val permissions: List<PermissionItem>,
  val chosenPermissions: Set<PermissionKind>,
  val grantedPermissions: Set<PermissionKind> = emptySet(),
  val style: DiaryStyle,
  val ramGb: Int,
  val models: List<ModelChoice>,
  val model: ModelChoice,
  val downloadProgress: Float? = null,
  val downloadPaused: Boolean = false,
  val lockEnabled: Boolean = false,
  val placeGuess: PlaceGuessOutput? = null,
  val placeAnswered: Boolean = false,
  val complete: Boolean = false,
) {
  val index: Int get() = steps.indexOf(step)
  val canGoBack: Boolean get() = index > 0 && !complete
  val canSkip: Boolean get() = step.skippable && !complete

  /** MODEL needs the download at least started (it may finish in the background); FIRST_PLACE needs an answer. */
  val canContinue: Boolean
    get() = !complete && when (step) {
      OnboardingStep.MODEL -> downloadProgress != null
      OnboardingStep.FIRST_PLACE -> placeAnswered
      else -> true
    }
}

/**
 * First-run flow (P3.6). Pure state machine: every intent returns the next state. Side effects stay outside common
 * code (permission dialogs, model download, biometric prompt) and come back as results; the only writes here are the
 * profile ([ProfileRepository]) and the first-place confirmation ([PlaceRepository.confirm]).
 */
class OnboardingPresenter(
  private val profile: ProfileRepository,
  private val places: PlaceRepository,
) {
  fun start(ramGb: Int, placeGuess: PlaceGuessOutput? = null): OnboardingUiState {
    val saved = profile.load()
    // Onboarding keeps offering only E2B (always) and E4B (when RAM allows); GEMMA3_1B is in the model manager.
    val models = listOf(ModelChoice.E2B, ModelChoice.E4B).filter { it == ModelChoice.E2B || ramGb >= it.minRamGb }
    return OnboardingUiState(
      step = OnboardingStep.WELCOME,
      steps = OnboardingStep.entries.filter { it != OnboardingStep.FIRST_PLACE || placeGuess != null },
      permissions = PERMISSIONS,
      chosenPermissions = DEFAULT_PERMISSIONS,
      style = saved.style,
      ramGb = ramGb,
      models = models,
      model = (saved.model as? ActiveModel.Catalog)?.choice?.takeIf { it in models } ?: ModelChoice.E2B,
      lockEnabled = saved.lockEnabled,
      placeGuess = placeGuess,
    )
  }

  /** Continue. Saves the profile on every step so a killed app resumes with the user's answers. */
  fun next(state: OnboardingUiState): OnboardingUiState = if (state.canContinue) advance(state) else state

  fun skip(state: OnboardingUiState): OnboardingUiState = if (state.canSkip) advance(state) else state

  fun back(state: OnboardingUiState): OnboardingUiState =
    if (state.canGoBack) state.copy(step = state.steps[state.index - 1]) else state

  /** Background location needs foreground location (Android asks for it separately, afterwards). */
  fun togglePermission(state: OnboardingUiState, kind: PermissionKind): OnboardingUiState {
    val on = kind !in state.chosenPermissions
    val chosen = when {
      on && kind == PermissionKind.BACKGROUND_LOCATION -> state.chosenPermissions + kind + PermissionKind.LOCATION
      !on && kind == PermissionKind.LOCATION -> state.chosenPermissions - kind - PermissionKind.BACKGROUND_LOCATION
      on -> state.chosenPermissions + kind
      else -> state.chosenPermissions - kind
    }
    return state.copy(chosenPermissions = chosen)
  }

  /** What to ask the system for, in the order Android accepts: foreground first, background last on its own. */
  fun permissionRequests(state: OnboardingUiState): List<PermissionKind> =
    PermissionKind.entries.filter { it in state.chosenPermissions && it !in state.grantedPermissions }

  fun permissionsResult(state: OnboardingUiState, granted: Set<PermissionKind>): OnboardingUiState =
    state.copy(grantedPermissions = state.grantedPermissions + granted)

  fun setParticle(state: OnboardingUiState, particle: EndingParticle) = state.copy(style = state.style.copy(particle = particle))

  fun setAddress(state: OnboardingUiState, address: AddressForm) = state.copy(style = state.style.copy(address = address))

  fun setNickname(state: OnboardingUiState, nickname: String) =
    state.copy(style = state.style.copy(nickname = nickname.take(ProfileStore.MAX_NICKNAME)))

  /** The model can change only before its download starts. */
  fun chooseModel(state: OnboardingUiState, model: ModelChoice): OnboardingUiState =
    if (state.downloadProgress == null && model in state.models) state.copy(model = model) else state

  fun downloadUpdate(state: OnboardingUiState, progress: Float, paused: Boolean = false): OnboardingUiState =
    state.copy(downloadProgress = progress.coerceIn(0f, 1f), downloadPaused = paused)

  /** Result of the system biometric prompt (D11: the app only learns pass/fail). */
  fun lockResult(state: OnboardingUiState, enabled: Boolean): OnboardingUiState {
    val next = state.copy(lockEnabled = enabled)
    save(next, onboarded = false)
    return next
  }

  /** "ใช่" or "ตั้งชื่ออื่น" (B7): the guess becomes a confirmed place, then the flow finishes. Blank label = no-op. */
  fun confirmPlace(state: OnboardingUiState, label: String): OnboardingUiState {
    val guess = state.placeGuess ?: return state
    val clean = label.trim()
    if (clean.isEmpty() || state.placeAnswered) return state
    if (guess.lat != null && guess.lon != null) places.addNew(listOf(PlaceRecord(guess.placeId, guess.lat, guess.lon))) // no-op when already stored
    if (!places.confirm(guess.placeId, clean, guess.category)) return state // not stored: do not pretend it was
    return advance(state.copy(placeAnswered = true))
  }

  /** "ไม่ใช่": nothing is stored, the place stays a guess. */
  fun rejectPlace(state: OnboardingUiState): OnboardingUiState =
    if (state.placeGuess == null || state.placeAnswered) state else advance(state.copy(placeAnswered = true))

  private fun advance(state: OnboardingUiState): OnboardingUiState {
    val last = state.index == state.steps.lastIndex
    save(state, onboarded = last)
    return if (last) state.copy(complete = true) else state.copy(step = state.steps[state.index + 1])
  }

  private fun save(state: OnboardingUiState, onboarded: Boolean) =
    profile.save(ProfileRecord(state.style, ActiveModel.Catalog(state.model), state.lockEnabled, onboarded))

  companion object {
    /** "มิ้นท์ วันนี้มีสามนัดนะคะ นัดแรก 09:30" (B4 preview) for the current style. */
    fun preview(style: DiaryStyle): String {
      val name = style.addressName?.let { "$it " }.orEmpty()
      val tail = when (style.particle) {
        EndingParticle.NONE -> "นะ"
        EndingParticle.KA -> "นะคะ" // ค่ะ turns into คะ after นะ
        EndingParticle.KRAP -> "นะครับ"
      }
      return "${name}วันนี้มีสามนัด$tail นัดแรก 09:30"
    }

    /** PDPA s.19: consent is never pre-ticked, every permission starts off and the user turns each one on. */
    val DEFAULT_PERMISSIONS = emptySet<PermissionKind>()

    /** Wording follows docs/permissions.md (purpose and consequence, PDPA s.19/s.23). */
    val PERMISSIONS = listOf(
      PermissionItem(
        PermissionKind.LOCATION,
        "ตำแหน่ง",
        "เพื่อเขียนไดอารีว่าวันนี้ไปไหน ขอพิกัดครั้งเดียวเมื่อคุณหยุดอยู่ที่ใดที่หนึ่ง ไม่เปิด GPS ตลอดเวลา ไม่เก็บเส้นทาง",
        "ถ้าไม่ให้ จะไม่มีไทม์ไลน์สถานที่ ไดอารีเหลือแค่นัดหมาย",
      ),
      PermissionItem(
        PermissionKind.ACTIVITY,
        "การเคลื่อนไหว",
        "รู้ว่าเดินหรือนั่งรถ ใช้ปลุก Haku แทนการเปิด GPS เก็บแค่ชนิดการเคลื่อนที่ ไม่นับก้าว ไม่วัดหัวใจ",
        "ถ้าไม่ให้ Haku จะไม่รู้ว่าเมื่อไรควรบันทึก ไทม์ไลน์จะไม่ทำงาน",
      ),
      PermissionItem(
        PermissionKind.CALENDAR,
        "ปฏิทิน",
        "อ่านนัดเมื่อวานถึงพรุ่งนี้อย่างเดียว เพื่อใส่ในไดอารีและเตรียมข้อมูลก่อนนัด ชื่อนัดอาจเป็นเรื่องส่วนตัว ลบรายการไหนก็ได้และจะไม่กลับมา",
        "ถ้าไม่ให้ ไดอารีจะไม่มีนัดหมาย",
      ),
      PermissionItem(
        PermissionKind.BACKGROUND_LOCATION,
        "ตำแหน่งตอนไม่ได้เปิดแอป",
        "Haku เก็บตำแหน่งแม้ปิดแอปหรือไม่ได้ใช้งาน เพื่อรู้ว่าคุณถึงและออกจากที่ไหนตลอดวัน ตำแหน่งไม่ออกจากเครื่อง ไม่แชร์ ไม่ใช้โฆษณา",
        "ถ้าไม่ให้ ไทม์ไลน์จะขาดเกือบทั้งหมด",
      ),
    )

    fun placeQuestion(category: PlaceCategory): String = when (category) {
      PlaceCategory.HOME -> "ที่นี่คือบ้านของคุณใช่ไหม"
      PlaceCategory.WORK -> "ที่นี่คือที่ทำงานของคุณใช่ไหม"
      PlaceCategory.OTHER -> "คุณมาที่นี่บ่อย ตั้งชื่อไว้ไหม"
    }

    fun placeLabel(category: PlaceCategory): String = when (category) {
      PlaceCategory.HOME -> "บ้าน"
      PlaceCategory.WORK -> "ที่ทำงาน"
      PlaceCategory.OTHER -> "ที่ประจำ"
    }
  }
}
