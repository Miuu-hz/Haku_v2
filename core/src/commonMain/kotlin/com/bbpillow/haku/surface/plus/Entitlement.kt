package com.bbpillow.haku.surface.plus

import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.Plan
import okio.ByteString.Companion.encodeUtf8

/**
 * P9.4 entitlement, pure. Lives in `surface` (not `network`): it decides what the screens may show, and core never
 * talks to the store itself; Google Play is reached only through androidApp/platform/billing.
 *
 * What the device last learned from Google Play. [tokenHash] is SHA-256 of the purchase token: the raw token is never
 * stored or logged. Losing Plus changes only which views unlock; no memory, diary or statistic is ever locked or deleted.
 */
data class EntitlementRecord(
  val plan: Plan = Plan.FREE,
  val productId: String? = null,
  /** When Google Play last answered a purchase query (epoch ms, UTC); 0 = never. */
  val lastVerifiedAtMs: Long = 0,
  val tokenHash: String? = null,
) {
  /**
   * End of Plus without a new answer from Play: [EntitlementRules.OFFLINE_GRACE_MS] after the last verification.
   * ponytail: the Play client gives no paid-through date (only the Play Developer API does, server side), so a cancelled
   * subscription can stay Plus offline up to 7 days past its real end; take expiryTime from a server when one exists.
   */
  val expiresAtMs: Long? get() = if (plan == Plan.PLUS) lastVerifiedAtMs + EntitlementRules.OFFLINE_GRACE_MS else null
}

/** An active Plus subscription as Play reported it. Build with [of]: it hashes the token so the raw one goes nowhere. */
data class PlusPurchase(val productId: String, val tokenHash: String) {
  companion object {
    fun of(productId: String, purchaseToken: String) = PlusPurchase(productId, hashToken(purchaseToken))

    fun hashToken(purchaseToken: String): String = purchaseToken.encodeUtf8().sha256().hex()
  }
}

object EntitlementRules {
  /** P9.4 pass criterion: Plus keeps working 7 days without internet. */
  const val OFFLINE_GRACE_MS = 7 * 86_400_000L

  /** Clock drift tolerated before a verification "from the future" stops counting (a clock set back to stretch the grace). */
  const val CLOCK_SKEW_MS = 3_600_000L

  /** Plus only while the last verification is recent; otherwise FREE (downgrade = views lock, nothing is deleted). */
  fun planAt(record: EntitlementRecord, nowMs: Long): Plan {
    if (record.plan != Plan.PLUS) return Plan.FREE
    val sinceVerified = nowMs - record.lastVerifiedAtMs
    return if (sinceVerified in -CLOCK_SKEW_MS until OFFLINE_GRACE_MS) Plan.PLUS else Plan.FREE
  }

  /**
   * A successful, complete purchase query at [nowMs]: [active] = the Plus subscription it returned, null = none.
   * A failed query (offline, Play unavailable) is not a verification: keep the old record and let [planAt] run the grace.
   */
  fun verified(active: PlusPurchase?, nowMs: Long): EntitlementRecord =
    if (active == null) EntitlementRecord(Plan.FREE, lastVerifiedAtMs = nowMs)
    else EntitlementRecord(Plan.PLUS, active.productId, nowMs, active.tokenHash)

  /** The live view the stats gate asks: re-evaluated on every read, so the grace ends even while the app stays open. */
  fun entitlementsOf(record: EntitlementRecord, nowMs: () -> Long): Entitlements = object : Entitlements {
    override val plan: Plan get() = planAt(record, nowMs())
  }
}

/**
 * Price test (P9.4: at least two price points). Fixed per install from its random id, so a person always sees the same
 * price; recorded only in the local entitlement file, never sent. Each maps to a Play base plan id of the same name.
 */
enum class PriceVariant(val basePlanId: String) {
  A("price-a"),
  B("price-b");

  companion object {
    fun of(installId: String): PriceVariant = if (installId.encodeUtf8().sha256()[0].toInt() and 1 == 0) A else B
  }
}
