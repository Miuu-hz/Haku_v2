package com.bbpillow.haku.platform.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.bbpillow.haku.capture.stats.Entitlements
import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.surface.plus.EntitlementRecord
import com.bbpillow.haku.surface.plus.EntitlementRepository
import com.bbpillow.haku.surface.plus.EntitlementRules
import com.bbpillow.haku.surface.plus.PlusOfferRecord
import com.bbpillow.haku.surface.plus.PlusOffers
import com.bbpillow.haku.surface.plus.PlusPurchase
import com.bbpillow.haku.surface.plus.PlusUiState
import com.bbpillow.haku.surface.plus.PricePhaseRecord
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * P9.4 Haku Plus over Google Play Billing: connect, restore (`queryPurchasesAsync`) on start and on demand, prices from
 * Play, purchase flow, acknowledgement, and the entitlement file (read and written only on [dispatcher], the app's single
 * DB thread). [entitlements] replaces AppGraph's old FREE constant; it re-reads the clock on every `plan` access, so the
 * 7-day offline grace ends on time.
 *
 * ponytail: on-device verification only (no backend). Play's client answers are trusted as-is, so a rooted device that
 * hooks the Billing Library or edits the entitlement file can unlock Plus views. Accepted: Plus unlocks only views of the
 * user's own on-device data (nothing server-side to steal) and the tier is cheap. Add Play Developer API verification of
 * the purchase token (plus RTDN for cancellations) on a server when a backend exists or fraud shows up in Play reports.
 */
class PlayBilling(
  context: Context,
  private val store: EntitlementRepository,
  private val dispatcher: CoroutineDispatcher,
  private val scope: CoroutineScope,
) {
  private val entitlementFlow = MutableStateFlow(live(EntitlementRecord()))
  val entitlements: StateFlow<Entitlements> = entitlementFlow

  private val uiFlow = MutableStateFlow(PlusUiState(isPlus = false, prices = emptyList()))
  /** What the Haku Plus screen shows: plan, Play-formatted prices, last outcome message. */
  val ui: StateFlow<PlusUiState> = uiFlow

  private val mutex = Mutex()
  @Volatile private var details: Map<String, ProductDetails> = emptyMap()
  @Volatile private var picked: List<PlusOfferRecord> = emptyList()

  /** The file is read once, off the main thread; its install id fixes the price variant for good. */
  private val variant = scope.async(dispatcher) {
    val file = store.load()
    entitlementFlow.value = live(file.entitlement)
    publish(null)
    file.priceVariant
  }

  private val client = BillingClient.newBuilder(context.applicationContext)
    .setListener { result, purchases -> scope.launch { onPurchasesUpdated(result, purchases.orEmpty()) } }
    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
    .enableAutoServiceReconnection()
    .build()

  /**
   * Restore: asks Play for this account's subscriptions and saves the answer. Returns false when Play could not be
   * reached; the saved record then stands and its grace keeps counting. Also loads prices the first time.
   */
  suspend fun refresh(): Boolean = mutex.withLock {
    variant.await()
    if (!connect()) return false
    val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build())
    if (result.billingResult.responseCode != BillingResponseCode.OK) {
      Log.w(TAG, "purchase query failed code=${result.billingResult.responseCode}")
      return false
    }
    apply(result.purchasesList, authoritative = true)
    if (picked.isEmpty()) loadOffers()
    true
  }

  /** The restore button: refresh, then say what happened. */
  suspend fun restore() {
    val message = when {
      !refresh() -> "เชื่อมต่อ Google Play ไม่ได้ ลองอีกครั้งเมื่อออนไลน์ (สิทธิ์เดิมยังใช้ได้ถึง 7 วัน)"
      isPlus() -> "กู้คืน Haku Plus แล้ว"
      else -> "ไม่พบการสมัคร Haku Plus ในบัญชี Google นี้"
    }
    publish(message)
  }

  /** Opens Play's purchase sheet for [productId] with the offer picked for this install. False = prices not loaded yet. */
  fun buy(activity: Activity, productId: String): Boolean {
    val offer = picked.firstOrNull { it.productId == productId } ?: return false
    val product = details[productId] ?: return false
    val params = BillingFlowParams.newBuilder()
      .setProductDetailsParamsList(
        listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offer.offerToken).build()),
      )
      .build()
    val code = client.launchBillingFlow(activity, params).responseCode
    if (code != BillingResponseCode.OK) publish("เปิดหน้าชำระเงินของ Google Play ไม่ได้ ลองอีกครั้ง")
    return code == BillingResponseCode.OK
  }

  private suspend fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
    when (result.responseCode) {
      BillingResponseCode.OK -> {
        mutex.withLock { apply(purchases, authoritative = false) }
        val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        publish(if (isPlus()) "ขอบคุณที่สนับสนุน Haku" else if (pending) "รอ Google Play ยืนยันการชำระเงิน" else null)
      }
      BillingResponseCode.USER_CANCELED -> publish(null)
      BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
      else -> publish("ซื้อไม่สำเร็จ ลองอีกครั้ง")
    }
  }

  /**
   * [authoritative] = the full list from a query (no active Plus there means downgrade); a purchase update lists only
   * what changed, so it can grant Plus but never take it away. Suspended (payment on hold) and pending do not count.
   * Downgrade only changes the saved plan: no memory, diary or statistic is touched.
   */
  private suspend fun apply(purchases: List<Purchase>, authoritative: Boolean) {
    val active = purchases.filter { p ->
      p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isSuspended && p.products.any { it in PlusProducts.ALL }
    }
    // Unacknowledged purchases are refunded by Play after 3 days; a failure here is retried on the next refresh.
    active.filterNot { it.isAcknowledged }.forEach { p ->
      val code = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()).responseCode
      if (code != BillingResponseCode.OK) Log.w(TAG, "acknowledge failed code=$code")
    }
    val first = active.firstOrNull()
    if (first == null && !authoritative) return
    val purchase = first?.let { p -> PlusPurchase.of(p.products.first { it in PlusProducts.ALL }, p.purchaseToken) }
    val record = EntitlementRules.verified(purchase, System.currentTimeMillis())
    withContext(dispatcher) { store.save(record) }
    entitlementFlow.value = live(record)
    publish(uiFlow.value.message)
  }

  private suspend fun loadOffers() {
    val products = PlusProducts.ALL.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.SUBS).build() }
    val result = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
    if (result.billingResult.responseCode != BillingResponseCode.OK) {
      Log.w(TAG, "product query failed code=${result.billingResult.responseCode}")
      return
    }
    val list = result.productDetailsList.orEmpty()
    details = list.associateBy { it.productId }
    picked = PlusOffers.pick(variant.await(), list.flatMap(::offersOf), PlusProducts.ALL)
    publish(uiFlow.value.message)
  }

  private fun offersOf(d: ProductDetails): List<PlusOfferRecord> = d.subscriptionOfferDetails.orEmpty().map { o ->
    PlusOfferRecord(
      productId = d.productId,
      basePlanId = o.basePlanId,
      offerId = o.offerId,
      tags = o.offerTags,
      offerToken = o.offerToken,
      phases = o.pricingPhases.pricingPhaseList.map { PricePhaseRecord(it.formattedPrice, it.billingPeriod, it.priceAmountMicros) },
    )
  }

  private suspend fun connect(): Boolean {
    if (client.isReady) return true
    return suspendCancellableCoroutine { cont ->
      client.startConnection(object : BillingClientStateListener {
        override fun onBillingSetupFinished(result: BillingResult) {
          if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
        }

        override fun onBillingServiceDisconnected() {
          if (cont.isActive) cont.resume(false)
        }
      })
    }
  }

  private fun isPlus() = entitlementFlow.value.plan == Plan.PLUS

  private fun publish(message: String?) {
    uiFlow.value = PlusOffers.uiState(isPlus(), picked, message)
  }

  private fun live(record: EntitlementRecord) = EntitlementRules.entitlementsOf(record) { System.currentTimeMillis() }

  private companion object {
    const val TAG = "HakuBilling"
  }
}
