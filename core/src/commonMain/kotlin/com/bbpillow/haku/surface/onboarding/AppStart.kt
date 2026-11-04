package com.bbpillow.haku.surface.onboarding

/** What the app shows on open (P3.6). */
enum class StartScreen { ONBOARDING, LOCKED, MAIN }

object AppStart {
  /** Onboarding until it finished once; after that the D11 lock (if on) until the system prompt passed this session. */
  fun screen(profile: ProfileRecord, unlocked: Boolean): StartScreen = when {
    !profile.onboarded -> StartScreen.ONBOARDING
    profile.lockEnabled && !unlocked -> StartScreen.LOCKED
    else -> StartScreen.MAIN
  }

  /**
   * Stays need a place fix and its wake-up (location + activity). Calendar alone is enough too: capture then reads the
   * calendar (and charging) without any location listener, so its events still reach the diary.
   */
  fun shouldStartCapture(granted: Set<PermissionKind>): Boolean =
    (PermissionKind.LOCATION in granted && PermissionKind.ACTIVITY in granted) || PermissionKind.CALENDAR in granted

  /**
   * Onboarding is marked done before its last button, so MAIN also starts capture if it never got started. When it is
   * already on, MAIN registers it again only after a force stop, which drops the wake-ups until boot or an update. Not on
   * every open: replacing the geofence can lose an exit Play services has not delivered yet.
   */
  fun shouldStartCaptureOnMain(granted: Set<PermissionKind>, captureEnabled: Boolean, wasForceStopped: Boolean): Boolean =
    if (captureEnabled) wasForceStopped else shouldStartCapture(granted)

  const val RELOCK_AFTER_MS = 60_000L

  /** D11 lock engages again once the app was in the background longer than [RELOCK_AFTER_MS]. */
  fun shouldRelock(backgroundedForMs: Long): Boolean = backgroundedForMs > RELOCK_AFTER_MS
}
