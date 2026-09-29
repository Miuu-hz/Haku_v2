package com.bbpillow.haku.surface.onboarding

import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import okio.FileSystem
import okio.Path

/** What onboarding decides once and the app reads on every start (D15 style, D7 model, D11 lock, completion flag). */
data class ProfileRecord(
  val style: DiaryStyle = DiaryStyle(),
  val model: ModelChoice = ModelChoice.E2B,
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
      model = ModelChoice.entries.firstOrNull { it.name == kv["model"] } ?: ModelChoice.E2B,
      lockEnabled = kv["lock"] == "true",
      onboarded = kv["onboarded"] == "true",
    )
  }

  override fun save(profile: ProfileRecord) {
    val content = listOf(
      "particle" to profile.style.particle.name,
      "address" to profile.style.address.name,
      "nickname" to cleanNickname(profile.style.nickname),
      "model" to profile.model.name,
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
