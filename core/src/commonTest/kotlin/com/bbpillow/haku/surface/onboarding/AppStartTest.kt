package com.bbpillow.haku.surface.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppStartTest {
  @Test
  fun notOnboardedAlwaysOnboards() {
    assertEquals(StartScreen.ONBOARDING, AppStart.screen(ProfileRecord(lockEnabled = true), unlocked = true))
  }

  @Test
  fun lockGatesMainUntilUnlocked() {
    val locked = ProfileRecord(lockEnabled = true, onboarded = true)
    assertEquals(StartScreen.LOCKED, AppStart.screen(locked, unlocked = false))
    assertEquals(StartScreen.MAIN, AppStart.screen(locked, unlocked = true))
    assertEquals(StartScreen.MAIN, AppStart.screen(ProfileRecord(onboarded = true), unlocked = false))
  }

  @Test
  fun mainStartsCaptureWhenPermittedAndRegistersItAgainWhenAlreadyOn() {
    val both = setOf(PermissionKind.LOCATION, PermissionKind.ACTIVITY)
    assertTrue(AppStart.shouldStartCaptureOnMain(both, captureEnabled = false)) // swiped away before the last button
    assertTrue(AppStart.shouldStartCaptureOnMain(both, captureEnabled = true)) // a force stop dropped the wake-ups
    assertFalse(AppStart.shouldStartCaptureOnMain(setOf(PermissionKind.LOCATION), captureEnabled = false))
  }

  @Test
  fun relocksOnlyAfterMoreThanTheGracePeriodInBackground() {
    assertFalse(AppStart.shouldRelock(backgroundedForMs = 60_000))
    assertTrue(AppStart.shouldRelock(backgroundedForMs = 60_001))
    assertFalse(AppStart.shouldRelock(backgroundedForMs = 0))
  }

  @Test
  fun captureNeedsLocationAndActivityOrCalendar() {
    assertTrue(AppStart.shouldStartCapture(setOf(PermissionKind.LOCATION, PermissionKind.ACTIVITY)))
    assertTrue(AppStart.shouldStartCapture(setOf(PermissionKind.CALENDAR))) // calendar-only: events still reach the diary
    assertTrue(AppStart.shouldStartCapture(setOf(PermissionKind.LOCATION, PermissionKind.CALENDAR)))
    assertFalse(AppStart.shouldStartCapture(setOf(PermissionKind.ACTIVITY)))
    assertFalse(AppStart.shouldStartCapture(setOf(PermissionKind.LOCATION)))
    assertFalse(AppStart.shouldStartCapture(emptySet()))
  }

  @Test
  fun mainStartsCalendarOnlyCaptureToo() {
    assertTrue(AppStart.shouldStartCaptureOnMain(setOf(PermissionKind.CALENDAR), captureEnabled = false))
  }
}
