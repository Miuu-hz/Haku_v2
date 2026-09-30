package com.bbpillow.haku.surface.onboarding

import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.PlaceGuessOutput
import com.bbpillow.haku.capture.PlaceRecord
import com.bbpillow.haku.capture.PlaceRepository
import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.DiaryStyle
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.onboarding.OnboardingStep.FIRST_PLACE
import com.bbpillow.haku.surface.onboarding.OnboardingStep.LOCK
import com.bbpillow.haku.surface.onboarding.OnboardingStep.MODEL
import com.bbpillow.haku.surface.onboarding.OnboardingStep.PERMISSIONS
import com.bbpillow.haku.surface.onboarding.OnboardingStep.PRIVACY
import com.bbpillow.haku.surface.onboarding.OnboardingStep.STYLE
import com.bbpillow.haku.surface.onboarding.OnboardingStep.WELCOME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

private class RecordingPlaces : PlaceRepository {
  val confirmed = mutableListOf<Triple<String, String, PlaceCategory>>()
  override fun list() = emptyList<PlaceRecord>()
  override fun addNew(places: List<PlaceRecord>) = 0
  override fun confirm(id: String, label: String, category: PlaceCategory): Boolean {
    confirmed += Triple(id, label, category)
    return true
  }
  override fun delete(id: String) = false
  override fun deleteAll() = Unit
}

class OnboardingPresenterTest {
  private val fs = FakeFileSystem()
  private val store = ProfileStore(fs, "/profile/profile.txt".toPath())
  private val places = RecordingPlaces()
  private val presenter = OnboardingPresenter(store, places)
  private val homeGuess = PlaceGuessOutput("p1", PlaceCategory.HOME, 5)

  /** Walks forward through [steps], asserting each; the model step starts a download first. */
  private fun OnboardingUiState.through(vararg steps: OnboardingStep): OnboardingUiState {
    var s = this
    for (expected in steps) {
      assertEquals(expected, s.step)
      s = presenter.next(if (s.step == MODEL) presenter.downloadUpdate(s, 0.1f) else s)
    }
    return s
  }

  @Test fun stepsFollowB1ToB7AndFirstPlaceOnlyWithAGuess() {
    assertEquals(listOf(WELCOME, PRIVACY, PERMISSIONS, STYLE, MODEL, LOCK), presenter.start(8).steps)
    assertEquals(listOf(WELCOME, PRIVACY, PERMISSIONS, STYLE, MODEL, LOCK, FIRST_PLACE), presenter.start(8, homeGuess).steps)
  }

  @Test fun backGoesToThePreviousStepAndNotBeforeTheFirst() {
    val start = presenter.start(8)
    assertEquals(start, presenter.back(start))
    val atPermissions = start.through(WELCOME, PRIVACY)
    assertEquals(PRIVACY, presenter.back(atPermissions).step)
  }

  @Test fun onlyOptionalStepsCanBeSkipped() {
    val start = presenter.start(8)
    assertEquals(start, presenter.skip(start)) // welcome
    val atStyle = presenter.skip(start.through(WELCOME, PRIVACY))
    assertEquals(STYLE, atStyle.step)
    assertEquals(atStyle, presenter.skip(atStyle)) // D15: must answer
    val atModel = presenter.next(atStyle)
    assertEquals(atModel, presenter.next(atModel), "no download started yet")
    assertEquals(LOCK, presenter.skip(atModel).step)
  }

  @Test fun particleAndAddressArePersistedWhenLeavingTheStyleStep() {
    var s = presenter.start(8).through(WELCOME, PRIVACY, PERMISSIONS)
    s = presenter.setParticle(s, EndingParticle.KA)
    s = presenter.setAddress(s, AddressForm.NICKNAME)
    s = presenter.setNickname(s, "มิ้นท์")
    presenter.next(s)
    assertEquals(DiaryStyle(EndingParticle.KA, AddressForm.NICKNAME, "มิ้นท์"), store.load().style)
    assertFalse(store.load().onboarded)
    assertEquals(DiaryStyle(EndingParticle.KA, AddressForm.NICKNAME, "มิ้นท์"), presenter.start(8).style, "resumes with the answer")
    assertEquals("มิ้นท์ วันนี้มีสามนัดนะคะ นัดแรก 09:30", OnboardingPresenter.preview(s.style))
    assertEquals("วันนี้มีสามนัดนะ นัดแรก 09:30", OnboardingPresenter.preview(DiaryStyle()))
  }

  @Test fun finishingSetsTheCompletionFlag() {
    var s = presenter.start(8).through(WELCOME, PRIVACY, PERMISSIONS, STYLE, MODEL)
    s = presenter.lockResult(s, true)
    s = presenter.next(s)
    assertTrue(s.complete)
    assertTrue(store.load().onboarded)
    assertTrue(store.load().lockEnabled)
    assertEquals(s, presenter.back(s))
  }

  @Test fun confirmingTheFirstPlaceStoresItThenFinishes() {
    var s = presenter.start(8, homeGuess).through(WELCOME, PRIVACY, PERMISSIONS, STYLE, MODEL, LOCK)
    assertEquals(FIRST_PLACE, s.step)
    assertFalse(s.canContinue)
    assertEquals(s, presenter.confirmPlace(s, "  "))
    s = presenter.confirmPlace(s, "บ้าน")
    assertEquals(listOf(Triple("p1", "บ้าน", PlaceCategory.HOME)), places.confirmed)
    assertTrue(s.complete)
    assertTrue(store.load().onboarded)
  }

  @Test fun rejectingTheFirstPlaceStoresNothing() {
    val s = presenter.rejectPlace(presenter.start(8, homeGuess).through(WELCOME, PRIVACY, PERMISSIONS, STYLE, MODEL, LOCK))
    assertTrue(s.complete)
    assertTrue(places.confirmed.isEmpty())
  }

  @Test fun backgroundLocationTravelsWithLocationAndIsAskedLast() {
    var s = presenter.start(8)
    assertEquals(OnboardingPresenter.DEFAULT_PERMISSIONS, s.chosenPermissions)
    s = presenter.togglePermission(presenter.togglePermission(s, PermissionKind.LOCATION), PermissionKind.BACKGROUND_LOCATION)
    assertTrue(PermissionKind.LOCATION in s.chosenPermissions)
    s = presenter.togglePermission(s, PermissionKind.CALENDAR)
    assertEquals(PermissionKind.BACKGROUND_LOCATION, presenter.permissionRequests(s).last())
    s = presenter.permissionsResult(s, setOf(PermissionKind.LOCATION))
    assertFalse(PermissionKind.LOCATION in presenter.permissionRequests(s))
    s = presenter.togglePermission(s, PermissionKind.LOCATION)
    assertFalse(PermissionKind.BACKGROUND_LOCATION in s.chosenPermissions)
  }

  @Test fun modelChoiceFollowsDeviceRam() {
    val small = presenter.start(8)
    assertEquals(listOf(ModelChoice.E2B), small.models)
    assertEquals(ModelChoice.E2B, presenter.chooseModel(small, ModelChoice.E4B).model)
    val big = presenter.start(12)
    assertEquals(ModelChoice.E2B, big.model) // D7 default
    val picked = presenter.chooseModel(big, ModelChoice.E4B)
    assertEquals(ModelChoice.E4B, picked.model)
    val downloading = presenter.downloadUpdate(picked, 0.64f, paused = true)
    assertEquals(ModelChoice.E4B, presenter.chooseModel(downloading, ModelChoice.E2B).model, "locked once started")
  }

  @Test fun profileStoreRoundTripsAndCleansTheNickname() {
    val style = DiaryStyle(EndingParticle.KRAP, AddressForm.NICKNAME, " บอส\nonboarded=true ")
    store.save(ProfileRecord(style, ModelChoice.E4B, lockEnabled = true, onboarded = false))
    val back = store.load()
    assertEquals("บอสonboarded=true", back.style.nickname)
    assertFalse(back.onboarded)
    assertEquals(ModelChoice.E4B, back.model)
    assertEquals(ProfileRecord(), ProfileStore(fs, "/nope/profile.txt".toPath()).load())
  }
}
