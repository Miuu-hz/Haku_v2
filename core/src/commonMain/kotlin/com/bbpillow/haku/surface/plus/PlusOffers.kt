package com.bbpillow.haku.surface.plus

/** One pricing phase as Google Play formats it. [billingPeriod] is ISO 8601 (`P1M`, `P1Y`). */
data class PricePhaseRecord(val formattedPrice: String, val billingPeriod: String, val priceMicros: Long)

/** A purchasable subscription offer, copied out of Play's ProductDetails so core can choose without Android types. */
data class PlusOfferRecord(
  val productId: String,
  val basePlanId: String,
  /** Null = the base plan itself; otherwise a promotional offer (e.g. tagged [FOUNDER_TAG]). */
  val offerId: String?,
  val tags: List<String>,
  val offerToken: String,
  /** In order; the last one is the recurring price. */
  val phases: List<PricePhaseRecord>,
)

/** One buy button. Every price string comes from Play (localized, with currency); none is written in the app. */
data class PlusPrice(val productId: String, val label: String, val price: String, val founderNote: String?)

data class PlusUiState(val isPlus: Boolean, val prices: List<PlusPrice>, val message: String? = null)

const val FOUNDER_TAG = "founder"

object PlusOffers {
  /**
   * One offer per product, in [productOrder]: the variant's base plan when Play has it (else any base plan of that
   * product, so a missing price-b never hides the product), preferring a founder offer the user is eligible for (Play
   * lists only eligible offers), then the plain base plan.
   */
  fun pick(variant: PriceVariant, offers: List<PlusOfferRecord>, productOrder: List<String>): List<PlusOfferRecord> =
    productOrder.mapNotNull { id ->
      val ofProduct = offers.filter { it.productId == id && it.phases.isNotEmpty() }
      val candidates = ofProduct.filter { it.basePlanId == variant.basePlanId }.ifEmpty { ofProduct }
      candidates.firstOrNull { FOUNDER_TAG in it.tags } ?: candidates.firstOrNull { it.offerId == null } ?: candidates.firstOrNull()
    }

  fun priceOf(offer: PlusOfferRecord): PlusPrice {
    val recurring = offer.phases.last()
    val first = offer.phases.first()
    val founder = when {
      offer.phases.size < 2 -> null
      first.priceMicros == 0L -> "ทดลองใช้ฟรีช่วงแรก แล้ว ${recurring.formattedPrice}${per(recurring.billingPeriod)}"
      first.priceMicros < recurring.priceMicros -> "ราคาผู้สนับสนุนรุ่นแรก ${first.formattedPrice}${per(first.billingPeriod)}"
      else -> null
    }
    return PlusPrice(offer.productId, label(recurring.billingPeriod), recurring.formattedPrice + per(recurring.billingPeriod), founder)
  }

  fun uiState(isPlus: Boolean, picked: List<PlusOfferRecord>, message: String? = null) =
    PlusUiState(isPlus, picked.map(::priceOf), message)

  private fun label(period: String) = when (period) {
    "P1M" -> "รายเดือน"
    "P1Y" -> "รายปี"
    else -> "แพ็กเกจ $period"
  }

  private fun per(period: String) = when (period) {
    "P1M" -> "/เดือน"
    "P1Y" -> "/ปี"
    else -> ""
  }
}
