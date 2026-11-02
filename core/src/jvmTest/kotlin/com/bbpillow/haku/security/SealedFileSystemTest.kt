package com.bbpillow.haku.security

import com.bbpillow.haku.memory.DailyEventInput
import com.bbpillow.haku.memory.MemoryStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Same scheme as the Android Keystore cipher, with a software key. */
class JvmGcmCipher(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : FileCipher {
  override fun seal(plain: ByteArray): ByteArray {
    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    return iv + c.doFinal(plain)
  }

  override fun open(sealed: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
      .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
    return c.doFinal(sealed, 12, sealed.size - 12)
  }
}

class SealedFileSystemTest {
  private val disk = FakeFileSystem()
  private val sealed = SealedFileSystem(disk, JvmGcmCipher())
  private val p = "/data/profile.txt".toPath()

  init { disk.createDirectories("/data".toPath()) }

  private fun rawText(path: okio.Path) = disk.read(path) { readByteArray() }.decodeToString()

  @Test fun roundTripsAndNeverWritesPlaintext() {
    sealed.write(p) { writeUtf8("ชื่อ: ฮากุ") }
    assertEquals("ชื่อ: ฮากุ", sealed.read(p) { readUtf8() })
    assertTrue(SealedFileSystem.isSealed(disk.read(p) { readByteArray() }))
    assertFalse(rawText(p).contains("ฮากุ"))
  }

  @Test fun legacyPlaintextReadsThenSealAllSealsIt() {
    disk.write(p) { writeUtf8("old") }
    assertEquals("old", sealed.read(p) { readUtf8() })
    assertEquals(1, sealed.sealAll("/data".toPath()))
    assertEquals(0, sealed.sealAll("/data".toPath()))
    assertTrue(SealedFileSystem.isSealed(disk.read(p) { readByteArray() }))
    assertEquals("old", sealed.read(p) { readUtf8() })
  }

  @Test fun appendKeepsEarlierContent() {
    sealed.write(p) { writeUtf8("a") }
    sealed.appendingSink(p).use { s -> okio.Buffer().writeUtf8("b").also { s.write(it, it.size) } }
    assertEquals("ab", sealed.read(p) { readUtf8() })
  }

  @Test fun otherKeyOrTamperFails() {
    sealed.write(p) { writeUtf8("secret") }
    assertFailsWith<Exception> { SealedFileSystem(disk, JvmGcmCipher()).read(p) { readUtf8() } }
    val raw = disk.read(p) { readByteArray() }.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    disk.write(p) { write(raw) }
    assertFailsWith<Exception> { sealed.read(p) { readUtf8() } }
  }

  @Test fun memoryStoreWorksThroughIt() {
    val store = MemoryStore(sealed, "/data/memory".toPath())
    store.appendDaily(DailyEventInput("ไปหาหมอฟัน", com.bbpillow.haku.memory.Provenance.USER_TYPED, 1_762_000_000_000))
    val day = store.listDays().single()
    assertEquals("ไปหาหมอฟัน", store.readDay(day).single().text)
    assertFalse(rawText("/data/memory/daily/$day.md".toPath()).contains("หมอฟัน"))
  }
}
