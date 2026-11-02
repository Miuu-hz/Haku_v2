package com.bbpillow.haku.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.bbpillow.haku.security.FileCipher
import com.bbpillow.haku.security.SealedFileSystem
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * D11 at-rest encryption. One AES-256-GCM key lives in the Android Keystore (never leaves it, not in backups) and
 * seals both the app's files ([files]) and the random SQLCipher key of `haku.db` ([openDatabase]).
 *
 * The key needs no user auth on purpose: capture, the evening summary and brief workers open the DB in the
 * background with the phone locked. The app lock ([BiometricLock]) stays a UI gate.
 * ponytail: no StrongBox (slow per call, many files); add for the DB key wrap only if a threat model needs it.
 */
object DataKeys {
  private const val ALIAS = "haku-d11"
  private const val DB_KEY_FILE = "haku.dbkey"
  private const val TAG = "HakuD11"

  private val key: SecretKey by lazy {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(ALIAS, null) as SecretKey?) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
      generateKey()
    }
  }

  /** IV (12 bytes, chosen by the Keystore) + ciphertext + 16-byte tag. */
  val cipher = object : FileCipher {
    override fun seal(plain: ByteArray): ByteArray {
      val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
      return c.iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
      val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
      return c.doFinal(sealed, 12, sealed.size - 12)
    }
  }

  /** The file system every personal-data store must use. */
  val files: SealedFileSystem by lazy { SealedFileSystem(FileSystem.SYSTEM, cipher) }

  /**
   * Opens `noBackupFilesDir/haku.db` with SQLCipher. A pre-D11 plaintext DB is converted first, so a dev install
   * keeps its data. Conversion is crash-safe: export to `.enc`, move the plaintext aside to `.plain`, move `.enc` in,
   * and drop `.plain` only after the encrypted DB opened. A crash at any step is finished on the next launch.
   * ponytail: a missing/invalidated Keystore key throws here (app can't start); a "data unreadable, reset?" screen
   * comes with the onboarding work, keys don't get invalidated without user-auth binding.
   */
  fun openDatabase(context: Context, db: File = File(context.noBackupFilesDir, "haku.db")): SQLiteConnection {
    System.loadLibrary("sqlcipher")
    val passphrase = dbPassphrase(context)
    val enc = File(db.path + ".enc")
    val plain = File(db.path + ".plain")
    if (!db.exists() && enc.exists() && plain.exists()) check(enc.renameTo(db)) { "could not finish the database swap" }
    if (isPlaintextSqlite(db)) encryptInPlace(db, enc, plain, passphrase)
    return driver(passphrase).open(db.path).also {
      if (plain.exists()) listOf("", "-wal", "-shm", "-journal").forEach { s -> File(plain.path + s).delete() }
    }
  }

  // SQLCipher's default handler deletes the file on "corruption", which is also what a wrong key looks like.
  // Never delete the user's data on our own: fail loudly and keep the file.
  private val keepFile = DatabaseErrorHandler { _, e -> Log.e(TAG, "haku.db reported corrupt, file kept", e) }

  private fun driver(passphrase: ByteArray) = SQLCipherDriver(passphrase, null, keepFile)

  /** Raw-key form `x'<64 hex>'` skips SQLCipher's PBKDF2, so opening stays instant; the key is already random. */
  private fun dbPassphrase(context: Context): ByteArray {
    val f = File(context.noBackupFilesDir, DB_KEY_FILE)
    val raw = if (f.exists()) cipher.open(f.readBytes()) else ByteArray(32).also {
      SecureRandom().nextBytes(it)
      val tmp = File(f.path + ".tmp")
      FileOutputStream(tmp).use { out -> out.write(cipher.seal(it)); out.fd.sync() } // durable before any DB uses it
      check(tmp.renameTo(f)) { "could not store the database key" }
    }
    return "x'${raw.joinToString("") { "%02x".format(it) }}'".encodeToByteArray()
  }

  private fun isPlaintextSqlite(db: File): Boolean = db.exists() && db.length() >= 16 &&
    ByteArray(16).also { b -> db.inputStream().use { it.read(b) } }.contentEquals("SQLite format 3\u0000".encodeToByteArray())

  private fun encryptInPlace(db: File, enc: File, plain: File, passphrase: ByteArray) {
    enc.delete() // a half-written export from an earlier crash
    driver(ByteArray(0)).open(db.path).use { c ->
      c.execSQL("ATTACH DATABASE '${enc.path.replace("'", "''")}' AS enc KEY \"${passphrase.decodeToString()}\"")
      c.prepare("SELECT sqlcipher_export('enc')").use { it.step() }
      c.execSQL("DETACH DATABASE enc")
    } // closing checkpoints the plaintext WAL, so haku.db alone holds everything
    listOf("-wal", "-shm", "-journal").forEach { s -> File(db.path + s).renameTo(File(plain.path + s)) }
    check(db.renameTo(plain)) { "could not move the plaintext database aside" }
    check(enc.renameTo(db)) { "could not swap in the encrypted database" }
    Log.i(TAG, "haku.db converted to SQLCipher")
  }

  /** One-time sweep: seals the pre-D11 plaintext files under the app's file dirs; a marker skips it afterwards. */
  fun sealLegacyFiles(context: Context): Int {
    val done = File(context.noBackupFilesDir, "d11.sealed")
    if (done.exists()) return 0
    val failed = mutableListOf<Path>()
    val sealed = listOf("memory", "summaries", "profile.txt", "entitlement.txt")
      .sumOf { files.sealAll(context.filesDir.resolve(it).path.toPath(), failed) }
    if (failed.isEmpty()) done.createNewFile() else Log.w(TAG, "${failed.size} files left unsealed, retry next launch")
    return sealed
  }
}
