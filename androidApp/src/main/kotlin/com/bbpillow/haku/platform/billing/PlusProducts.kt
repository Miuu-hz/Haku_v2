package com.bbpillow.haku.platform.billing

/**
 * Haku Plus subscription product ids, the only place they are written.
 * ponytail: placeholders until they are created in Play Console (docs/FOR-YOU.md section 23): two subscriptions with
 * exactly these ids, each with base plans `price-a` and `price-b` (the price test, PriceVariant) and an optional
 * offer tagged `founder` for the first-supporter price. Prices live in Play only; the app never hard-codes one.
 */
object PlusProducts {
  const val MONTHLY = "haku_plus_monthly"
  const val YEARLY = "haku_plus_yearly"

  /** Also the order of the buy buttons. */
  val ALL = listOf(MONTHLY, YEARLY)
}
