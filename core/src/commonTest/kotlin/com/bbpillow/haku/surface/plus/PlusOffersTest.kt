package com.bbpillow.haku.surface.plus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlusOffersTest {
  private val order = listOf("haku_plus_monthly", "haku_plus_yearly")

  private fun offer(product: String, base: String, offerId: String? = null, tags: List<String> = emptyList(), vararg phases: PricePhaseRecord) =
    PlusOfferRecord(product, base, offerId, tags, "tok-$product-$base-$offerId", phases.toList())

  private val month99 = PricePhaseRecord("฿99.00", "P1M", 99_000_000)
  private val month79 = PricePhaseRecord("฿79.00", "P1M", 79_000_000)
  private val month49 = PricePhaseRecord("฿49.00", "P1M", 49_000_000)
  private val year990 = PricePhaseRecord("฿990.00", "P1Y", 990_000_000)

  @Test fun picksTheVariantsBasePlanPreferringFounderAndKeepsProductOrder() {
    val offers = listOf(
      offer("haku_plus_yearly", "price-a", null, emptyList(), year990),
      offer("haku_plus_monthly", "price-a", null, emptyList(), month99),
      offer("haku_plus_monthly", "price-a", "founder-1", listOf(FOUNDER_TAG), month49, month99),
      offer("haku_plus_monthly", "price-b", null, emptyList(), month79),
    )
    val a = PlusOffers.pick(PriceVariant.A, offers, order)
    assertEquals(listOf("haku_plus_monthly", "haku_plus_yearly"), a.map { it.productId })
    assertEquals("founder-1", a[0].offerId)
    val b = PlusOffers.pick(PriceVariant.B, offers, order)
    assertEquals("price-b", b[0].basePlanId)
    assertEquals("price-a", b[1].basePlanId) // yearly has no price-b: fall back instead of hiding it
  }

  @Test fun pricesComeFromPlayStrings() {
    val founder = PlusOffers.priceOf(offer("haku_plus_monthly", "price-a", "f", listOf(FOUNDER_TAG), month49, month99))
    assertEquals(PlusPrice("haku_plus_monthly", "รายเดือน", "฿99.00/เดือน", "ราคาผู้สนับสนุนรุ่นแรก ฿49.00/เดือน"), founder)
    val yearly = PlusOffers.priceOf(offer("haku_plus_yearly", "price-a", null, emptyList(), year990))
    assertEquals("รายปี", yearly.label)
    assertNull(yearly.founderNote)
    val trial = PlusOffers.priceOf(offer("haku_plus_yearly", "price-a", "t", emptyList(), PricePhaseRecord("Free", "P1W", 0), year990))
    assertEquals("ทดลองใช้ฟรีช่วงแรก แล้ว ฿990.00/ปี", trial.founderNote)
  }

  @Test fun missingProductsAreSkipped() {
    assertEquals(emptyList(), PlusOffers.pick(PriceVariant.A, emptyList(), order))
  }
}
