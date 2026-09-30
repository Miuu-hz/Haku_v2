package com.bbpillow.haku.surface.plus

import com.bbpillow.haku.capture.stats.Plan
import com.bbpillow.haku.capture.stats.StatsFeature
import com.bbpillow.haku.capture.stats.allows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class EntitlementTest {
  private val day = 86_400_000L
  private val t0 = 1_790_000_000_000L
  private val plus = EntitlementRules.verified(PlusPurchase.of("haku_plus_monthly", "raw-token-123"), t0)

  @Test fun verifiedPurchaseIsPlusAndKeepsOnlyTheTokenHash() {
    assertEquals(Plan.PLUS, plus.plan)
    assertEquals("haku_plus_monthly", plus.productId)
    assertEquals(64, plus.tokenHash!!.length)
    assertFalse("raw-token-123" in plus.toString())
    assertEquals(t0 + 7 * day, plus.expiresAtMs)
  }

  @Test fun plusHoldsSevenDaysOfflineThenDropsToFree() {
    assertEquals(Plan.PLUS, EntitlementRules.planAt(plus, t0))
    assertEquals(Plan.PLUS, EntitlementRules.planAt(plus, t0 + 7 * day - 1))
    assertEquals(Plan.FREE, EntitlementRules.planAt(plus, t0 + 7 * day))
  }

  @Test fun clockSetBackFarDoesNotStretchTheGrace() {
    assertEquals(Plan.PLUS, EntitlementRules.planAt(plus, t0 - 60_000))
    assertEquals(Plan.FREE, EntitlementRules.planAt(plus, t0 - 2 * day))
  }

  @Test fun verificationWithNoPurchaseDowngradesAndFreeNeverBecomesPlus() {
    val free = EntitlementRules.verified(null, t0 + day)
    assertEquals(Plan.FREE, EntitlementRules.planAt(free, t0 + day))
    assertNull(free.expiresAtMs)
    assertEquals(Plan.FREE, EntitlementRules.planAt(EntitlementRecord(), t0))
  }

  @Test fun liveEntitlementsReEvaluateTheClockAndGateOnlyViews() {
    var now = t0
    val e = EntitlementRules.entitlementsOf(plus) { now }
    assertTrue(e.allows(StatsFeature.MONTH_SUMMARY))
    now = t0 + 8 * day
    assertFalse(e.allows(StatsFeature.MONTH_SUMMARY))
    assertTrue(e.allows(StatsFeature.WEEK_SUMMARY)) // free keeps its view
  }

  @Test fun priceVariantIsStablePerInstallAndBothOccur() {
    assertEquals(PriceVariant.of("abc"), PriceVariant.of("abc"))
    val seen = (0 until 64).map { PriceVariant.of("install-$it") }.toSet()
    assertEquals(PriceVariant.entries.toSet(), seen)
  }

  @Test fun storeRoundTripsAndFixesTheInstallId() {
    val fs = FakeFileSystem()
    val store = EntitlementStore(fs, "/files/entitlement.txt".toPath())
    val first = store.load()
    assertEquals(EntitlementRecord(), first.entitlement)
    store.save(plus)
    val again = store.load()
    assertEquals(plus, again.entitlement)
    assertEquals(first.installId, again.installId)
    val text = fs.read("/files/entitlement.txt".toPath()) { readUtf8() }
    assertFalse("raw-token-123" in text)
    assertTrue("price_variant=${first.priceVariant.name}" in text)
  }

  @Test fun damagedFileReadsAsFree() {
    val fs = FakeFileSystem()
    val path = "/files/entitlement.txt".toPath()
    fs.createDirectories(path.parent!!)
    fs.write(path) { writeUtf8("plan=PLUSS\nverified=zzz\ninstall=keep-me\n") }
    val r = EntitlementStore(fs, path).load()
    assertEquals(Plan.FREE, r.entitlement.plan)
    assertEquals("keep-me", r.installId)
  }

  @Test fun twoInstallsGetDifferentIds() {
    val a = EntitlementStore(FakeFileSystem(), "/e.txt".toPath()).load().installId
    val b = EntitlementStore(FakeFileSystem(), "/e.txt".toPath()).load().installId
    assertNotEquals(a, b)
  }
}
