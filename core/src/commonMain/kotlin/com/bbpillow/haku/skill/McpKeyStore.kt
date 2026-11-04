package com.bbpillow.haku.skill

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * File-backed [McpKeyRepository] over [fs] (in production, `DataKeys.files` for D11 sealed storage).
 *
 * One file per server under [root], named by sha256 hex of the url + ".key", written via temp file + atomic move.
 * Keys and URLs are never logged and never included in exception messages.
 */
class McpKeyStore(
  private val fs: FileSystem,
  private val root: Path,
) : McpKeyRepository {

  override fun find(url: String): String? = runCatching {
    val path = pathOf(url)
    if (!fs.exists(path)) return null
    fs.read(path) { readUtf8() }
  }.getOrNull()

  override fun put(url: String, key: String) {
    fs.createDirectories(root)
    val path = pathOf(url)
    val tmp = root / "${path.name}.tmp"
    fs.write(tmp) { writeUtf8(key) }
    fs.atomicMove(tmp, path)
  }

  override fun remove(url: String) {
    fs.delete(pathOf(url), mustExist = false)
  }

  private fun pathOf(url: String): Path = root / "${url.encodeUtf8().sha256().hex()}$EXT"

  companion object {
    private const val EXT = ".key"
  }
}

object McpKeys {
  private val SECRET_QUERY_PARAMS = setOf(
    "key",
    "api_key",
    "apikey",
    "token",
    "access_token",
    "secret",
    "password",
  )

  /** Trims; blank gives null. */
  fun clean(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

  /** True only for 1..4096 printable ASCII characters (0x21..0x7E), so a key cannot inject a header line. */
  fun isUsable(key: String): Boolean {
    if (key.length !in 1..4096) return false
    return key.all { it.code in 0x21..0x7E }
  }

  /**
   * True when the query string has a parameter named key, api_key, apikey, token, access_token, secret or password,
   * ignoring case. A url with no query is false. Must not throw on odd input.
   */
  fun urlHoldsSecret(url: String): Boolean = runCatching {
    val withoutFragment = url.substringBefore('#')
    val question = withoutFragment.indexOf('?')
    if (question < 0) return false
    val query = withoutFragment.substring(question + 1)
    if (query.isEmpty()) return false
    query.split('&').any { part ->
      val name = part.substringBefore('=').trim().lowercase()
      name in SECRET_QUERY_PARAMS
    }
  }.getOrDefault(false)
}
