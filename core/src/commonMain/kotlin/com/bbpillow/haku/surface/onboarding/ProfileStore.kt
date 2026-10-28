package com.bbpillow.haku.surface.onboarding

import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import okio.FileSystem
import okio.Path

/** Reference to the active on-device model: a built-in catalog [ModelChoice], a user-imported `.litertlm` file, or [None]. */
sealed interface ActiveModel {
  data class Catalog(val choice: ModelChoice) : ActiveModel
  data class Imported(val fileName: String) : ActiveModel
  data object None : ActiveModel

  fun encode(): String = when (this) {
    is Catalog -> choice.name
    is Imported -> "$IMPORT_PREFIX$fileName"
    None -> NONE_VALUE
  }

  companion object {
    const val IMPORT_PREFIX = "import:"
    const val NONE_VALUE = "none"
    val DEFAULT: ActiveModel = Catalog(ModelChoice.E2B)

    /** Rejects path traversal (`/`, `\`, `..`), blank names and control characters. */
    fun isSafeFileName(name: String): Boolean =
      name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..") && name.none { it.isISOControl() }

    fun parse(raw: String?): ActiveModel {
      if (raw == null) return DEFAULT
      if (raw == NONE_VALUE) return None
      if (raw.startsWith(IMPORT_PREFIX)) {
        val name = raw.removePrefix(IMPORT_PREFIX)
        return if (isSafeFileName(name)) Imported(name) else DEFAULT
      }
      val choice = ModelChoice.entries.firstOrNull { it.name == raw }
      return if (choice != null) Catalog(choice) else DEFAULT
    }
  }
}

/** What onboarding decides once and the app reads on every start (D15 style, D7 model, D11 lock, completion flag). */
data class ProfileRecord(
  val style: DiaryStyle = DiaryStyle(),
  val model: ActiveModel = ActiveModel.Catalog(ModelChoice.E2B),
  val lockEnabled: Boolean = false,
  val onboarded: Boolean = false,
)

interface ProfileRepository {
  fun load(): ProfileRecord
  fun save(profile: ProfileRecord)
}

/**
 * The profile as a small `key=value` file, written by temp file + atomic rename (as MemoryStore).
 * Unknown or damaged values fall back to defaults, so a bad file re-runs onboarding instead of crashing.
 */
class ProfileStore(private val fs: FileSystem, private val file: Path) : ProfileRepository {
  override fun load(): ProfileRecord {
    if (!fs.exists(file)) return ProfileRecord()
    val kv = fs.read(file) { readUtf8() }.lines().mapNotNull { line ->
      line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
    }.toMap()
    return ProfileRecord(
      style = DiaryStyle(
        particle = EndingParticle.entries.firstOrNull { it.name == kv["particle"] } ?: EndingParticle.NONE,
        address = AddressForm.entries.firstOrNull { it.name == kv["address"] } ?: AddressForm.NONE,
        nickname = kv["nickname"].orEmpty(),
      ),
      model = ActiveModel.parse(kv["model"]),
      lockEnabled = kv["lock"] == "true",
      onboarded = kv["onboarded"] == "true",
    )
  }

  override fun save(profile: ProfileRecord) {
    val content = listOf(
      "particle" to profile.style.particle.name,
      "address" to profile.style.address.name,
      "nickname" to cleanNickname(profile.style.nickname),
      "model" to profile.model.encode(),
      "lock" to profile.lockEnabled.toString(),
      "onboarded" to profile.onboarded.toString(),
    ).joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" }
    file.parent?.let { fs.createDirectories(it) }
    val tmp = file.parent!! / "${file.name}.tmp"
    fs.write(tmp) { writeUtf8(content) }
    fs.atomicMove(tmp, file)
  }

  companion object {
    const val MAX_NICKNAME = 40

    /** One line, trimmed, bounded: the nickname is user input and ends up in every greeting. */
    fun cleanNickname(raw: String): String =
      raw.filterNot { it == '\n' || it == '\r' }.trim().take(MAX_NICKNAME)
  }
}
