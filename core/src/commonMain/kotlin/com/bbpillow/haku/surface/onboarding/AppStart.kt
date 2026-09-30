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

  /** Capture needs a place fix and its wake-up; without either it would only log charging and calendar. */
  fun shouldStartCapture(granted: Set<PermissionKind>): Boolean =
    PermissionKind.LOCATION in granted && PermissionKind.ACTIVITY in granted
}
