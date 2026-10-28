package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.ModelKind
import com.bbpillow.haku.surface.onboarding.OnboardingPresenter
import com.bbpillow.haku.surface.onboarding.ProfileRepository
import com.bbpillow.haku.surface.onboarding.ProfileStore
import kotlin.math.round

/** One `.litertlm` file (or catalog `.part` partial download) present on disk in the app's models or imports folder. */
data class InstalledModelRecord(
  val fileName: String,
  val bytes: Long,
  val imported: Boolean = false,
  val partBytes: Long = 0L,
)

/** Live download progress for a catalog model from the platform downloader. */
data class DownloadProgressInput(
  val choice: ModelChoice,
  val fraction: Float,
  val paused: Boolean,
  val done: Boolean = false,
  val error: String? = null,
)

enum class ModelStatus {
  NOT_DOWNLOADED,
  DOWNLOADING,
  DOWNLOADED,
  ACTIVE,
}

/** One card in the "AI ในเครื่อง" list (built-in catalog model or user-imported `.litertlm` file). */
data class ModelCardItem(
  val ref: ActiveModel,
  val choice: ModelChoice?,
  val fileName: String,
  val title: String,
  val sizeLabel: String,
  val note: String?,
  val minRamGb: Int?,
  val ramWarning: Boolean,
  val status: ModelStatus,
  val downloadFraction: Float = 0f,
  val downloadPaused: Boolean = false,
  val isImported: Boolean = false,
)

data class ModelManagerUiState(
  val ramGb: Int,
  val activeModel: ActiveModel,
  val activeModelLabel: String,
  val models: List<ModelCardItem>,
  val importingFileName: String? = null,
  val importFraction: Float = 0f,
  val message: String? = null,
)

data class SettingsUiState(
  val activeModelLabel: String,
  val style: DiaryStyle,
  val stylePreview: String,
  val lockEnabled: Boolean,
  val message: String? = null,
)

/**
 * Pure state assembly for Settings ("ตั้งค่า") and the on-device AI model manager ("AI ในเครื่อง").
 * File listing, HTTP downloads, SAF file copies and biometric prompts happen in `androidApp` and report in as values.
 */
// ponytail: one presenter owns both Settings and Model Manager; installed files come in as a plain list from androidApp.
class ModelManagerPresenter(
  private val profile: ProfileRepository,
) {
  fun loadSettings(installed: List<InstalledModelRecord>, message: String? = null): SettingsUiState {
    val saved = profile.load()
    return SettingsUiState(
      activeModelLabel = activeModelLabel(installed, saved.model),
      style = saved.style,
      stylePreview = OnboardingPresenter.preview(saved.style),
      lockEnabled = saved.lockEnabled,
      message = message,
    )
  }

  fun setParticle(particle: EndingParticle, installed: List<InstalledModelRecord>): SettingsUiState {
    val cur = profile.load()
    profile.save(cur.copy(style = cur.style.copy(particle = particle)))
    return loadSettings(installed)
  }

  fun setAddress(address: AddressForm, installed: List<InstalledModelRecord>): SettingsUiState {
    val cur = profile.load()
    profile.save(cur.copy(style = cur.style.copy(address = address)))
    return loadSettings(installed)
  }

  fun setNickname(nickname: String, installed: List<InstalledModelRecord>): SettingsUiState {
    val cur = profile.load()
    profile.save(cur.copy(style = cur.style.copy(nickname = nickname.take(ProfileStore.MAX_NICKNAME))))
    return loadSettings(installed)
  }

  fun setLock(enabled: Boolean, installed: List<InstalledModelRecord>): SettingsUiState {
    val cur = profile.load()
    profile.save(cur.copy(lockEnabled = enabled))
    return loadSettings(installed)
  }

  fun load(
    ramGb: Int,
    installed: List<InstalledModelRecord>,
    download: DownloadProgressInput? = null,
    importingFileName: String? = null,
    importFraction: Float = 0f,
    message: String? = null,
  ): ModelManagerUiState {
    val saved = profile.load()
    val catalogFiles = ModelChoice.entries.map { it.fileName }.toSet()

    val catalogCards = ModelChoice.entries.map { choice ->
      val entry = installed.firstOrNull { !it.imported && it.fileName == choice.fileName }
      val record = entry?.takeIf { it.bytes > 0L }
      val partBytes = entry?.partBytes?.coerceAtLeast(0L) ?: 0L
      val isInstalled = record != null
      val dl = download?.takeIf {
        it.choice == choice && !it.done && !(it.fraction <= 0f && it.error != null && partBytes == 0L)
      }
      val hasPartial = !isInstalled && (dl != null || partBytes > 0L)
      val status = when {
        // ponytail: an embedding model has no "use this" choice; once on disk it is simply on (ACTIVE hides the button)
        isInstalled && choice.kind == ModelKind.EMBEDDING -> ModelStatus.ACTIVE
        isInstalled && saved.model == ActiveModel.Catalog(choice) -> ModelStatus.ACTIVE
        isInstalled -> ModelStatus.DOWNLOADED
        hasPartial -> ModelStatus.DOWNLOADING
        else -> ModelStatus.NOT_DOWNLOADED
      }
      val fraction = if (hasPartial) {
        (dl?.fraction?.takeIf { it > 0f } ?: (partBytes.toFloat() / choice.bytes)).coerceIn(0f, 1f)
      } else {
        0f
      }
      ModelCardItem(
        ref = ActiveModel.Catalog(choice),
        choice = choice,
        fileName = choice.fileName,
        title = choice.title,
        sizeLabel = formatSize(record?.bytes ?: choice.bytes),
        note = choice.note,
        minRamGb = choice.minRamGb,
        ramWarning = ramGb < choice.minRamGb,
        status = status,
        downloadFraction = fraction,
        downloadPaused = if (hasPartial) (dl?.paused ?: true) else false,
        isImported = false,
      )
    }

    val importedCards = installed
      .filter { it.bytes > 0L && (it.imported || it.fileName !in catalogFiles) }
      .map { record ->
        val ref = ActiveModel.Imported(record.fileName)
        ModelCardItem(
          ref = ref,
          choice = null,
          fileName = record.fileName,
          title = displayNameOf(record.fileName),
          sizeLabel = formatSize(record.bytes),
          note = null,
          minRamGb = null,
          ramWarning = false,
          status = if (saved.model == ref) ModelStatus.ACTIVE else ModelStatus.DOWNLOADED,
          isImported = true,
        )
      }

    return ModelManagerUiState(
      ramGb = ramGb,
      activeModel = saved.model,
      activeModelLabel = activeModelLabel(installed, saved.model),
      models = catalogCards + importedCards,
      importingFileName = importingFileName,
      importFraction = importFraction.coerceIn(0f, 1f),
      message = message ?: download?.error,
    )
  }

  /** Marks [target] as the active model in [ProfileRepository] and returns the updated state. */
  fun setActive(
    target: ActiveModel,
    ramGb: Int,
    installed: List<InstalledModelRecord>,
    download: DownloadProgressInput? = null,
  ): ModelManagerUiState {
    val cur = profile.load()
    if (isChat(target)) profile.save(cur.copy(model = target))
    return load(ramGb, installed, download)
  }

  /** If no installed model is currently active, activates [candidate] once it finishes downloading or importing. */
  fun activateIfNoneReady(candidate: ActiveModel, installed: List<InstalledModelRecord>) {
    if (!isChat(candidate)) return
    val otherInstalled = installed.filterNot { matches(it, candidate, ModelChoice.entries.map { c -> c.fileName }.toSet()) }
    if (!isReady(profile.load().model, otherInstalled)) {
      val cur = profile.load()
      profile.save(cur.copy(model = candidate))
    }
  }

  /**
   * Updates the active model when [deleted] is removed. If [deleted] was active, falls back to another installed
   * catalog or imported model, else [ActiveModel.None]. Never touches memory or diary stores.
   */
  fun deleteModel(
    deleted: ActiveModel,
    ramGb: Int,
    installedAfterDelete: List<InstalledModelRecord>,
    download: DownloadProgressInput? = null,
  ): ModelManagerUiState {
    val cur = profile.load()
    val catalogFiles = ModelChoice.entries.map { it.fileName }.toSet()
    val remaining = installedAfterDelete.filterNot { (it.bytes <= 0L && it.partBytes <= 0L) || matches(it, deleted, catalogFiles) }
    if (cur.model == deleted) {
      val nextCatalog = ModelChoice.entries.firstOrNull { c ->
        c.kind == ModelKind.CHAT && remaining.any { !it.imported && it.fileName == c.fileName && it.bytes > 0L }
      }?.let { ActiveModel.Catalog(it) }
      val nextImported = remaining.firstOrNull { it.bytes > 0L && (it.imported || it.fileName !in catalogFiles) }
        ?.let { ActiveModel.Imported(it.fileName) }
      val fallback = nextCatalog ?: nextImported ?: ActiveModel.None
      profile.save(cur.copy(model = fallback))
    }
    return load(ramGb, remaining, download)
  }

  /** Short display name of the active model when its file is installed, or [NO_MODEL_LABEL] when none is ready. */
  fun activeModelLabel(installed: List<InstalledModelRecord>, active: ActiveModel = profile.load().model): String {
    if (!isReady(active, installed)) return NO_MODEL_LABEL
    return when (active) {
      is ActiveModel.Catalog -> active.choice.title
      is ActiveModel.Imported -> displayNameOf(active.fileName)
      ActiveModel.None -> NO_MODEL_LABEL
    }
  }

  /** Only chat models may become [ProfileRecord.model]; embedding models run beside it. */
  private fun isChat(ref: ActiveModel): Boolean = (ref as? ActiveModel.Catalog)?.choice?.kind != ModelKind.EMBEDDING

  private fun isReady(active: ActiveModel, installed: List<InstalledModelRecord>): Boolean {
    val catalogFiles = ModelChoice.entries.map { it.fileName }.toSet()
    return installed.any { it.bytes > 0L && matches(it, active, catalogFiles) }
  }

  private fun matches(record: InstalledModelRecord, ref: ActiveModel, catalogFiles: Set<String>): Boolean = when (ref) {
    is ActiveModel.Catalog -> !record.imported && record.fileName == ref.choice.fileName
    is ActiveModel.Imported -> (record.imported || record.fileName !in catalogFiles) && record.fileName == ref.fileName
    ActiveModel.None -> false
  }

  companion object {
    const val NO_MODEL_LABEL = "ยังไม่มีโมเดล"
    const val RAM_WARNING_TEXT = "เครื่องนี้ RAM น้อยกว่าที่แนะนำ อาจช้าหรือปิดเอง"
    const val BUSY_DOWNLOAD_TEXT = "รอให้ตัวที่โหลดอยู่เสร็จก่อน"
    const val INVALID_IMPORT_TEXT = "รองรับเฉพาะไฟล์ .litertlm (LiteRT-LM)"
    const val FOOTER_NOTE = "โมเดลทำงานในเครื่องนี้ทั้งหมด ข้อมูลของคุณไม่ถูกส่งออกไปไหน · ดาวน์โหลดจาก Hugging Face ครั้งเดียว"
    const val LITERTLM_SUFFIX = ".litertlm"

    fun displayNameOf(fileName: String): String =
      if (fileName.endsWith(LITERTLM_SUFFIX, ignoreCase = true) && fileName.length > LITERTLM_SUFFIX.length) {
        fileName.dropLast(LITERTLM_SUFFIX.length)
      } else {
        fileName.substringBeforeLast('.').ifBlank { fileName }
      }

    fun formatSize(bytes: Long): String {
      if (bytes >= 100_000_000L) {
        val gb = round(bytes / 100_000_000.0) / 10.0
        return "$gb GB"
      }
      val mb = (bytes / 1_000_000L).coerceAtLeast(1L)
      return "$mb MB"
    }
  }
}
