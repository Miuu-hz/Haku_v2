package com.bbpillow.haku.capture.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationAppsTest {
  private val line = "jp.naver.line.android"

  @Test fun everyAppHasItsOwnSwitchAndIsOffUntilTurnedOn() {
    assertEquals(15, NotificationApps.ALL.map { it.packageName }.toSet().size)
    NotificationApps.ALL.forEach { assertFalse(NotificationApps.mayRead(it.packageName, emptySet())) }
  }

  @Test fun onlyTheAppTurnedOnIsRead() {
    assertTrue(NotificationApps.mayRead(line, setOf(line)))
    assertFalse(NotificationApps.mayRead("com.whatsapp", setOf(line)))
  }

  @Test fun aStoredPackageOutsideTheListIsNeverRead() {
    assertFalse(NotificationApps.mayRead("com.example.bank", setOf("com.example.bank")))
  }

  @Test fun theRuleListsFollowThePickerList() {
    assertEquals(NotificationApps.ALL.map { it.packageName }.toSet(), NotificationAppointmentRule.CHAT_APPS)
    assertEquals(
      setOf("com.instagram.android", "com.twitter.android", "com.linkedin.android", "com.discord", "com.Slack", "com.microsoft.teams"),
      NotificationAppointmentRule.MESSAGE_ONLY_APPS,
    )
  }
}
