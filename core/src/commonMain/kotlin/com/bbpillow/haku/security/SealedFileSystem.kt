package com.bbpillow.haku.security

import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import okio.Source

/** Whole-blob authenticated encryption (AES-GCM on Android: Keystore key, random IV inside the blob). */
interface FileCipher {
  fun seal(plain: ByteArray): ByteArray
  /** Throws if the blob was tampered with or sealed under another key. */
  fun open(sealed: ByteArray): ByteArray
}

/**
 * D11: every file written through this system lands on disk as `MAGIC + cipher.seal(bytes)`; reads open it again.
 * A file without [MAGIC] is a pre-D11 plaintext file: it reads as-is and is sealed on its next write, or by [sealAll].
 * Files are small (markdown days, a profile line), so whole-file buffering is fine.
 * ponytail: metadata().size reports the sealed size; nothing in the app reads sizes of these files.
 */
class SealedFileSystem(delegate: FileSystem, private val cipher: FileCipher) : ForwardingFileSystem(delegate) {

  override fun source(file: Path): Source = Buffer().write(open(delegate.read(file) { readByteArray() }))

  override fun sink(file: Path, mustCreate: Boolean): Sink {
    if (mustCreate && delegate.exists(file)) throw okio.IOException("$file already exists")
    return SealOnClose(file, Buffer())
  }

  override fun appendingSink(file: Path, mustExist: Boolean): Sink {
    val exists = delegate.exists(file)
    if (mustExist && !exists) throw okio.IOException("$file does not exist")
    return SealOnClose(file, Buffer().apply { if (exists) write(open(delegate.read(file) { readByteArray() })) })
  }

  /** Re-seals every plaintext file under [dir] (one-time sweep after upgrading to D11). Returns how many it sealed. */
  fun sealAll(dir: Path): Int {
    if (!delegate.exists(dir)) return 0
    val files = if (delegate.metadata(dir).isDirectory) delegate.listRecursively(dir).toList() else listOf(dir)
    return files.count { f ->
      if (!delegate.metadata(f).isRegularFile) return@count false
      val raw = delegate.read(f) { readByteArray() }
      if (isSealed(raw)) return@count false
      write(f) { write(raw) }
      true
    }
  }

  private fun open(raw: ByteArray): ByteArray = if (isSealed(raw)) cipher.open(raw.copyOfRange(MAGIC.size, raw.size)) else raw

  private inner class SealOnClose(private val file: Path, private val buffer: Buffer) : Sink by buffer {
    private var closed = false
    override fun close() {
      if (closed) return
      closed = true
      val sealed = cipher.seal(buffer.readByteArray())
      delegate.write(file) { write(MAGIC); write(sealed) }
    }
  }

  companion object {
    /** "HKE1": Haku encrypted, format 1. Plain UTF-8 markdown/profile text never starts with these bytes plus 0x00. */
    val MAGIC = byteArrayOf(0x48, 0x4B, 0x45, 0x31, 0x00)

    fun isSealed(raw: ByteArray): Boolean = raw.size >= MAGIC.size && MAGIC.indices.all { raw[it] == MAGIC[it] }
  }
}
