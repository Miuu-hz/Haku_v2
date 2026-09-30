package com.bbpillow.haku.surface.plus

import com.bbpillow.haku.capture.stats.Plan
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/** The entitlement file plus this install's price-test identity. Everything here stays on the device. */
data class EntitlementFileRecord(val entitlement: EntitlementRecord, val installId: String) {
  val priceVariant: PriceVariant get() = PriceVariant.of(installId)
}

interface EntitlementRepository {
  /** Creates the install id on first call, so the price variant is fixed from then on. */
  fun load(): EntitlementFileRecord
  fun save(entitlement: EntitlementRecord)
}

/**
 * `key=value` file written by temp file + atomic rename (as ProfileStore). Not thread-safe: call on the app's single
 * DB dispatcher. A damaged file reads as FREE (never Plus by accident) and keeps its install id when readable.
 */
class EntitlementStore(private val fs: FileSystem, private val file: Path) : EntitlementRepository {
  override fun load(): EntitlementFileRecord {
    val kv = if (fs.exists(file)) parse(fs.read(file) { readUtf8() }) else emptyMap()
    val installId = kv["install"]?.takeIf { it.isNotBlank() }
    val record = EntitlementRecord(
      plan = if (kv["plan"] == Plan.PLUS.name) Plan.PLUS else Plan.FREE,
      productId = kv["product"]?.takeIf { it.isNotEmpty() },
      lastVerifiedAtMs = kv["verified"]?.toLongOrNull() ?: 0,
      tokenHash = kv["token_sha256"]?.takeIf { it.isNotEmpty() },
    )
    if (installId != null) return EntitlementFileRecord(record, installId)
    val created = EntitlementFileRecord(record, Random.nextBytes(16).toByteString().hex())
    write(created)
    return created
  }

  override fun save(entitlement: EntitlementRecord) = write(load().copy(entitlement = entitlement))

  private fun write(r: EntitlementFileRecord) {
    val content = listOf(
      "plan" to r.entitlement.plan.name,
      "product" to r.entitlement.productId.orEmpty(),
      "verified" to r.entitlement.lastVerifiedAtMs.toString(),
      "token_sha256" to r.entitlement.tokenHash.orEmpty(),
      "install" to r.installId,
      "price_variant" to r.priceVariant.name,
    ).joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" }
    file.parent?.let { fs.createDirectories(it) }
    val tmp = file.parent!! / "${file.name}.tmp"
    fs.write(tmp) { writeUtf8(content) }
    fs.atomicMove(tmp, file)
  }

  private fun parse(text: String): Map<String, String> = text.lines().mapNotNull { line ->
    line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
  }.toMap()
}
