package com.bbpillow.haku.skill

/**
 * MCP servers the user added (remote only: Android cannot run stdio servers). Their tools become [SkillRecord]s with
 * source MCP, `action = mcp.call` and [SkillRecord.server] = [host], so mentions (`@host`), the picker and the gate
 * treat them like any other skill. Every tool starts disabled; the user turns each one on.
 *
 * @property host what `@` shows and the card names as the destination, e.g. `weather.example`.
 * @property tools the server's tool list as fetched when added/refreshed (input schema reduced to <= 3 string slots;
 *   a tool needing more is listed but cannot be enabled).
 */
data class McpServerRecord(
  val url: String,
  val host: String,
  val name: String,
  val description: String,
  val tools: List<SkillRecord>,
  val addedUtcMs: Long,
)

/** Persisted through the sealed file system (D11). Removing a server removes its tools from every list at once. */
interface McpServerRepository {
  fun all(): List<McpServerRecord>
  fun upsert(server: McpServerRecord)
  fun remove(url: String)
  fun setToolEnabled(url: String, tool: String, enabled: Boolean)
}

/**
 * Talks to a server. Android implementation: `io.modelcontextprotocol:kotlin-sdk` (as Gallery), streamable HTTP.
 * Each connect/list/call is logged as a connection (E3) by the implementation.
 */
interface McpClient {
  /** Connects, reads server info and tools. Throws with a Thai message on failure (shown in the add dialog). */
  suspend fun describe(url: String, nowUtcMs: Long): McpServerRecord

  /** Calls one tool with the validated slots; returns the tool's text result (trimmed to what the chat shows). */
  suspend fun call(url: String, tool: String, arguments: Map<String, String>): String
}
