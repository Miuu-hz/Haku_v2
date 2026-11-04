package com.bbpillow.haku.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class McpKeyStoreTest {
  private val fs = FakeFileSystem()
  private val root = "/mcp-keys".toPath()
  private val store = McpKeyStore(fs, root)

  private val url1 = "https://weather.example/mcp"
  private val url2 = "https://calendar.example/mcp"

  @Test fun roundTripPreservesKey() {
    store.put(url1, "secret-key-123")
    assertEquals("secret-key-123", store.find(url1))
  }

  @Test fun replaceOverwritesExistingKey() {
    store.put(url1, "first-key")
    store.put(url1, "second-key")
    assertEquals("second-key", store.find(url1))
  }

  @Test fun removeThenFindIsNull() {
    store.put(url1, "secret-key")
    store.remove(url1)
    assertNull(store.find(url1))
  }

  @Test fun removeWhenAbsentDoesNotFail() {
    store.remove("https://absent.example/mcp")
    assertNull(store.find("https://absent.example/mcp"))
  }

  @Test fun twoUrlsKeepSeparateKeys() {
    store.put(url1, "key-1")
    store.put(url2, "key-2")
    assertEquals("key-1", store.find(url1))
    assertEquals("key-2", store.find(url2))

    store.remove(url1)
    assertNull(store.find(url1))
    assertEquals("key-2", store.find(url2))
  }

  @Test fun noFileNameContainsTheKeyOrTheUrl() {
    val sensitiveUrl = "https://sensitive.example.org/private/mcp"
    val sensitiveKey = "super-secret-token-xyz"
    store.put(sensitiveUrl, sensitiveKey)

    val files = fs.list(root)
    assertTrue(files.isNotEmpty())
    for (file in files) {
      assertFalse(file.name.contains("sensitive"), "file name must not contain URL parts")
      assertFalse(file.name.contains("private"), "file name must not contain URL parts")
      assertFalse(file.name.contains("super"), "file name must not contain key parts")
      assertFalse(file.name.contains("secret"), "file name must not contain key parts")
      assertFalse(file.name.contains(sensitiveUrl), "file name must not contain URL")
      assertFalse(file.name.contains(sensitiveKey), "file name must not contain key")
      assertTrue(file.name.endsWith(".key"), "file name must end with .key")
    }
  }

  @Test fun findReturnsNullWhenUnreadable() {
    store.put(url1, "key-data")
    val file = fs.list(root).single()
    fs.delete(file)
    fs.createDirectories(file)
    assertNull(store.find(url1))
  }

  @Test fun cleanTrimsAndBlankGivesNull() {
    assertEquals("token", McpKeys.clean("  token  "))
    assertEquals("token", McpKeys.clean("token"))
    assertEquals("a b", McpKeys.clean("  a b  "))
    assertNull(McpKeys.clean(""))
    assertNull(McpKeys.clean("   "))
    assertNull(McpKeys.clean("\t\r\n "))
  }

  @Test fun isUsableValidatesPrintableAsciiAndLength() {
    // normal key
    assertTrue(McpKeys.isUsable("sk-ant-api03-abcdef123456"))
    assertTrue(McpKeys.isUsable("A-Z_a-z.0-9~`!@#$%^&*()+=[]{}\\|:;\"'<>,.?/"))

    // space
    assertFalse(McpKeys.isUsable(" "))
    assertFalse(McpKeys.isUsable("bearer token"))

    // newline
    assertFalse(McpKeys.isUsable("\n"))
    assertFalse(McpKeys.isUsable("header\r\ninjection"))
    assertFalse(McpKeys.isUsable("\r"))

    // Thai
    assertFalse(McpKeys.isUsable("คีย์"))
    assertFalse(McpKeys.isUsable("key-ไทย"))

    // empty
    assertFalse(McpKeys.isUsable(""))

    // length: 4096 chars ok, 4097 chars false
    assertTrue(McpKeys.isUsable("x".repeat(4096)))
    assertFalse(McpKeys.isUsable("x".repeat(4097)))
  }

  @Test fun urlHoldsSecretIdentifiesSecretParameters() {
    // ?key=a
    assertTrue(McpKeys.urlHoldsSecret("?key=a"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com/mcp?key=a"))

    // user:password@
    assertTrue(McpKeys.urlHoldsSecret("https://user:pw@example.com/mcp"))
    assertFalse(McpKeys.urlHoldsSecret("https://example.com/mcp/a@b"))

    // ?API_KEY=a
    assertTrue(McpKeys.urlHoldsSecret("?API_KEY=a"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com/mcp?API_KEY=a"))

    // ?a=1&token=b
    assertTrue(McpKeys.urlHoldsSecret("?a=1&token=b"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com/mcp?a=1&token=b"))

    // Additional required secret parameter names
    assertTrue(McpKeys.urlHoldsSecret("https://example.com?apikey=v"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com?access_token=v"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com?secret=v"))
    assertTrue(McpKeys.urlHoldsSecret("https://example.com?password=v"))

    // ?monkey=1 is false
    assertFalse(McpKeys.urlHoldsSecret("?monkey=1"))
    assertFalse(McpKeys.urlHoldsSecret("https://example.com/mcp?monkey=1"))

    // no query is false
    assertFalse(McpKeys.urlHoldsSecret("https://example.com/mcp"))
    assertFalse(McpKeys.urlHoldsSecret("https://example.com"))
    assertFalse(McpKeys.urlHoldsSecret(""))

    // garbage is false
    assertFalse(McpKeys.urlHoldsSecret("garbage"))
    assertFalse(McpKeys.urlHoldsSecret(":::invalid:::"))
    assertFalse(McpKeys.urlHoldsSecret("?"))
    assertFalse(McpKeys.urlHoldsSecret("?&&&"))
    assertFalse(McpKeys.urlHoldsSecret("?=key"))
  }
}
