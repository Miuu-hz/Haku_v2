package com.bbpillow.haku.surface.settings

import com.bbpillow.haku.capture.diary.AddressForm
import com.bbpillow.haku.capture.diary.EndingParticle
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.onboarding.ProfileRecord
import com.bbpillow.haku.surface.onboarding.ProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class ModelManagerPresenterTest {
  private val fs = FakeFileSystem()
  private val store = ProfileStore(fs, "/profile/profile.txt".toPath())
  private val presenter = ModelManagerPresenter(store)

  @Test fun catalogAndImportedStatesReflectDiskActiveAndDownloadProgress() {
    store.save(ProfileRecord(model = ActiveModel.Catalog(ModelChoice.E2B), onboarded = true))
    val installed = listOf(
      InstalledModelRecord(ModelChoice.E2B.fileName, ModelChoice.E2B.bytes, imported = false),
      InstalledModelRecord(ModelChoice.GEMMA3_1B.fileName, ModelChoice.GEMMA3_1B.bytes, imported = false),
      InstalledModelRecord("my-thai-gemma.litertlm", 1_500_000_000L, imported = true),
    )
    val downloading = DownloadProgressInput(ModelChoice.E4B, fraction = 0.42f, paused = true)

    val ui = presenter.load(ramGb = 8, installed = installed, download = downloading)
    assertEquals("Gemma 4 E2B", ui.activeModelLabel)
    assertEquals(4, ui.models.size)

    val e2b = ui.models.first { it.choice == ModelChoice.E2B }
    assertEquals(ModelStatus.ACTIVE, e2b.status)
    assertFalse(e2b.ramWarning)

    val e4b = ui.models.first { it.choice == ModelChoice.E4B }
    assertEquals(ModelStatus.DOWNLOADING, e4b.status)
    assertEquals(0.42f, e4b.downloadFraction)
    assertTrue(e4b.downloadPaused)
    assertTrue(e4b.ramWarning, "8 GB device < 12 GB minRamGb for E4B")

    val g3 = ui.models.first { it.choice == ModelChoice.GEMMA3_1B }
    assertEquals(ModelStatus.DOWNLOADED, g3.status)
    assertFalse(g3.ramWarning)

    val imp = ui.models.first { it.isImported }
    assertEquals("my-thai-gemma", imp.title)
    assertEquals(ModelStatus.DOWNLOADED, imp.status)
    assertEquals("1.5 GB", imp.sizeLabel)
    assertFalse(imp.ramWarning)
  }

  @Test fun notDownloadedCatalogShowsRamWarningWhenDeviceRamBelowMin() {
    val ui = presenter.load(ramGb = 6, installed = emptyList(), download = null)
    assertEquals(ModelManagerPresenter.NO_MODEL_LABEL, ui.activeModelLabel)

    val e2b = ui.models.first { it.choice == ModelChoice.E2B }
    assertEquals(ModelStatus.NOT_DOWNLOADED, e2b.status)
    assertTrue(e2b.ramWarning, "6 GB < 8 GB")

    val e4b = ui.models.first { it.choice == ModelChoice.E4B }
    assertEquals(ModelStatus.NOT_DOWNLOADED, e4b.status)
    assertTrue(e4b.ramWarning, "6 GB < 12 GB")

    val g3 = ui.models.first { it.choice == ModelChoice.GEMMA3_1B }
    assertEquals(ModelStatus.NOT_DOWNLOADED, g3.status)
    assertFalse(g3.ramWarning, "6 GB >= 6 GB")
  }

  @Test fun setActiveSwitchesBetweenCatalogAndImportedModels() {
    val installed = listOf(
      InstalledModelRecord(ModelChoice.E2B.fileName, ModelChoice.E2B.bytes, imported = false),
      InstalledModelRecord("custom.litertlm", 800_000_000L, imported = true),
    )
    val afterImportActive = presenter.setActive(
      target = ActiveModel.Imported("custom.litertlm"),
      ramGb = 12,
      installed = installed,
      download = null,
    )
    assertEquals("custom", afterImportActive.activeModelLabel)
    assertEquals(ActiveModel.Imported("custom.litertlm"), store.load().model)
    assertEquals(ModelStatus.ACTIVE, afterImportActive.models.first { it.isImported }.status)
    assertEquals(ModelStatus.DOWNLOADED, afterImportActive.models.first { it.choice == ModelChoice.E2B }.status)
  }

  @Test fun deletingActiveModelFallsBackToAnotherInstalledOrNone() {
    store.save(ProfileRecord(model = ActiveModel.Catalog(ModelChoice.E2B), onboarded = true))

    // 1) Delete active E2B while GEMMA3_1B and an imported model remain -> falls back to GEMMA3_1B
    val remainingAfterE2B = listOf(
      InstalledModelRecord(ModelChoice.GEMMA3_1B.fileName, ModelChoice.GEMMA3_1B.bytes, imported = false),
      InstalledModelRecord("local.litertlm", 900_000_000L, imported = true),
    )
    val state1 = presenter.deleteModel(
      deleted = ActiveModel.Catalog(ModelChoice.E2B),
      ramGb = 12,
      installedAfterDelete = remainingAfterE2B,
      download = null,
    )
    assertEquals(ActiveModel.Catalog(ModelChoice.GEMMA3_1B), store.load().model)
    assertEquals("Gemma 3 1B", state1.activeModelLabel)
    assertEquals(ModelStatus.ACTIVE, state1.models.first { it.choice == ModelChoice.GEMMA3_1B }.status)

    // 2) Delete active GEMMA3_1B while only imported model remains -> falls back to imported
    val remainingOnlyImported = listOf(
      InstalledModelRecord("local.litertlm", 900_000_000L, imported = true),
    )
    val state2 = presenter.deleteModel(
      deleted = ActiveModel.Catalog(ModelChoice.GEMMA3_1B),
      ramGb = 12,
      installedAfterDelete = remainingOnlyImported,
      download = null,
    )
    assertEquals(ActiveModel.Imported("local.litertlm"), store.load().model)
    assertEquals("local", state2.activeModelLabel)
    assertEquals(ModelStatus.ACTIVE, state2.models.first { it.isImported }.status)

    // 3) Delete active imported model when nothing else is installed -> falls back to None
    val state3 = presenter.deleteModel(
      deleted = ActiveModel.Imported("local.litertlm"),
      ramGb = 12,
      installedAfterDelete = emptyList(),
      download = null,
    )
    assertEquals(ActiveModel.None, store.load().model)
    assertEquals(ModelManagerPresenter.NO_MODEL_LABEL, state3.activeModelLabel)
    assertNull(state3.models.firstOrNull { it.status == ModelStatus.ACTIVE })
  }

  @Test fun settingsUpdatesPersistStyleAndLock() {
    val installed = listOf(InstalledModelRecord(ModelChoice.E2B.fileName, ModelChoice.E2B.bytes, imported = false))
    presenter.setParticle(EndingParticle.KA, installed)
    presenter.setAddress(AddressForm.NICKNAME, installed)
    presenter.setNickname("แพรว", installed)
    val s = presenter.setLock(true, installed)

    assertEquals(EndingParticle.KA, s.style.particle)
    assertEquals(AddressForm.NICKNAME, s.style.address)
    assertEquals("แพรว", s.style.nickname)
    assertTrue(s.lockEnabled)
    assertEquals("Gemma 4 E2B", s.activeModelLabel)
    assertTrue(store.load().lockEnabled)
  }
}
