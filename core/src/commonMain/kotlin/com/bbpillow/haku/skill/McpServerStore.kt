package com.bbpillow.haku.skill

import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/**
 * File-backed [McpServerRepository] over [fs] (in production, `DataKeys.files` for D11 encryption).
 *
 * One file per server under [root], written via temp file + atomic move. Format:
 * ```
 * url: https://weather.example/mcp
 * host: weather.example
 * name: Weather
 * description: Tools: forecast
 * addedUtcMs: 1700000000000
 * =====
 * enabled: false
 * ---
 * name: forecast
 * ...
 * ```
 * Not thread-safe: callers serialize access on the capture/DB dispatcher.
 */
class McpServerStore(
  private val fs: FileSystem,
  private val root: Path,
) : McpServerRepository {

  override fun all(): List<McpServerRecord> {
    if (!fs.exists(root)) return emptyList()
    return fs.list(root)
      .filter { it.name.endsWith(EXT) }
      .mapNotNull { read(it) }
      .sortedBy { it.addedUtcMs }
  }

  override fun upsert(server: McpServerRecord) {
    // Every MCP tool starts disabled when a server is added; the user turns each one on via setToolEnabled.
    val normalized = server.copy(
      tools = server.tools.map {
        it.copy(source = SkillSource.MCP, enabled = false, server = server.host)
      },
    )
    write(pathOf(server.url), normalized)
  }

  override fun remove(url: String) {
    fs.delete(pathOf(url), mustExist = false)
  }

  override fun setToolEnabled(url: String, tool: String, enabled: Boolean) {
    val path = pathOf(url)
    val existing = read(path) ?: return
    val updated = existing.copy(
      tools = existing.tools.map { t ->
        if (t.name == tool) t.copy(enabled = enabled && !isTooManySlots(t.description)) else t
      },
    )
    write(path, updated)
  }

  private fun pathOf(url: String): Path = root / "${url.encodeUtf8().sha256().hex()}$EXT"

  private fun write(path: Path, server: McpServerRecord) {
    fs.createDirectories(root)
    val content = serialize(server)
    val tmp = root / "${path.name}.tmp"
    fs.write(tmp) { writeUtf8(content) }
    fs.atomicMove(tmp, path)
  }

  private fun read(path: Path): McpServerRecord? = runCatching {
    if (!fs.exists(path)) return null
    parse(fs.read(path) { readUtf8() })
  }.getOrNull()

  private fun serialize(server: McpServerRecord): String = buildString {
    appendLine("url: ${cleanLine(server.url)}")
    appendLine("host: ${cleanLine(server.host)}")
    appendLine("name: ${cleanLine(server.name)}")
    appendLine("description: ${cleanLine(server.description)}")
    append("addedUtcMs: ${server.addedUtcMs}")
    for (tool in server.tools) {
      val allowed = tool.enabled && !isTooManySlots(tool.description)
      append("\n$SEPARATOR\n")
      append("enabled: $allowed\n")
      append(SkillFormat.write(tool))
    }
  }

  private fun parse(raw: String): McpServerRecord? {
    val normalized = raw.replace("\r\n", "\n")
    val blocks = normalized.split("\n$SEPARATOR\n")
    val meta = blocks.first().lines().filter { ':' in it }
      .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    val url = meta["url"]?.takeIf { it.isNotBlank() } ?: return null
    val host = meta["host"].orEmpty()
    val name = meta["name"].orEmpty()
    val description = meta["description"].orEmpty()
    val addedUtcMs = meta["addedUtcMs"]?.toLongOrNull() ?: 0L
    val tools = blocks.drop(1).map { parseToolBlock(it, host) }
    return McpServerRecord(
      url = url,
      host = host,
      name = name,
      description = description,
      tools = tools,
      addedUtcMs = addedUtcMs,
    )
  }

  private fun parseToolBlock(block: String, host: String): SkillRecord {
    val trimmed = block.trimStart()
    val hasEnabled = trimmed.startsWith("enabled:")
    val enabled = if (hasEnabled) {
      trimmed.substringBefore('\n').substringAfter(':').trim() == "true"
    } else {
      false
    }
    val md = if (hasEnabled) trimmed.substringAfter('\n') else trimmed
    // SkillFormat requires non-blank description and slots; wrap empty values so parameterless MCP tools round-trip.
    val emptyDesc = "\ndescription: \n" in md
    val emptySlots = "\nslots: \n" in md
    val patched = md
      .let { if (emptyDesc) it.replaceFirst("\ndescription: \n", "\ndescription: -\n") else it }
      .let { if (emptySlots) it.replaceFirst("\nslots: \n", "\nslots: _=-\n") else it }
    val skill = SkillFormat.parse(patched, SkillSource.MCP)
    val finalDesc = if (emptyDesc) "" else skill.description
    return skill.copy(
      description = finalDesc,
      slots = if (emptySlots) emptyList() else skill.slots,
      enabled = enabled && !isTooManySlots(finalDesc),
      server = host,
    )
  }

  private fun cleanLine(s: String): String = s.replace('\r', ' ').replace('\n', ' ').trim()

  companion object {
    private const val EXT = ".mcp"
    private const val SEPARATOR = "====="
    const val TOO_MANY_SLOTS_PREFIX = "(ใช้ไม่ได้: ต้องการข้อมูลเกิน 3 ช่อง) "

    fun isTooManySlots(description: String): Boolean = description.startsWith(TOO_MANY_SLOTS_PREFIX)
  }
}
