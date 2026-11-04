/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified for Haku (2026): derived from Google AI Edge Gallery 1.0.19
// customtasks/agentchat/McpManagerViewModel.kt and tools/RunMcpTool.kt.
// Replaced Hilt/DataStore/Firebase with stateless McpClient over Ktor Android + StreamableHttpClientTransport,
// enforced HTTPS (except 127.0.0.1 in debug), 15 s describe / 60 s call timeout, mapped tools to SkillRecord (<= 3 string slots,
// default disabled), capped tool output at 2000 chars, and logged every connection to E3 ConnectionRepository.

package com.bbpillow.haku.platform.mcp

import android.util.Log
import com.bbpillow.haku.BuildConfig
import com.bbpillow.haku.network.ConnectionPurpose
import com.bbpillow.haku.network.ConnectionRecord
import com.bbpillow.haku.skill.McpClient
import com.bbpillow.haku.skill.McpServerRecord
import com.bbpillow.haku.skill.McpServerStore
import com.bbpillow.haku.skill.McpToolSlots
import com.bbpillow.haku.skill.SkillActions
import com.bbpillow.haku.skill.SkillRecord
import com.bbpillow.haku.skill.SkillSlot
import com.bbpillow.haku.skill.SkillSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Android [McpClient] using `io.modelcontextprotocol:kotlin-sdk` streamable HTTP over Ktor Android engine.
 *
 * Every network attempt is recorded via [onConnection] (E3 connection log).
 */
class KtorMcpClient(
  private val allowLoopbackHttp: Boolean = BuildConfig.DEBUG,
  private val onConnection: suspend (ConnectionRecord) -> Unit = {},
) : McpClient {

  private val httpClient = HttpClient(Android) {
    install(SSE)
    followRedirects = false // a server's key must reach that server only: a redirect fails instead of being followed
    engine {
      connectTimeout = TIMEOUT_MS
      // read timeout: an SSE tool answer can stay silent until the server finishes (DeepWiki ask ~16 s)
      socketTimeout = CALL_TIMEOUT_MS
    }
  }

  // Maps (serverUrl, sanitizedSkillName) -> original MCP tool name for servers that use underscores/caps.
  private val rawToolNames = ConcurrentHashMap<Pair<String, String>, String>()

  override suspend fun describe(url: String, nowUtcMs: Long, apiKey: String?): McpServerRecord {
    val uri = validateMcpUrl(url, allowLoopbackHttp)
    val cleanUrl = uri.toString()
    val host = uri.host.lowercase()
    val client = newSdkClient()
    var bytesIn: Long? = null
    var result = FAILED
    return try {
      withTimeout(TIMEOUT_MS.toLong()) {
        client.connect(transport(cleanUrl, apiKey))
        val toolsResponse = client.listTools()
        val used = mutableSetOf<String>()
        val mapped = toolsResponse.tools.map { tool ->
          val baseSkill = mapMcpTool(tool, host)
          var uniqueName = baseSkill.name
          var idx = 2
          while (!used.add(uniqueName)) {
            val suffix = "-$idx"
            uniqueName = baseSkill.name.take(40 - suffix.length).trimEnd('-') + suffix
            idx++
          }
          val skill = if (uniqueName == baseSkill.name) baseSkill else baseSkill.copy(name = uniqueName)
          rawToolNames[cleanUrl to skill.name] = tool.name
          skill
        }
        val serverName = client.serverVersion?.name?.takeIf { it.isNotBlank() } ?: host
        val desc = if (mapped.isNotEmpty()) {
          mapped.joinToString(", ") { it.name }
        } else {
          "ไม่มีเครื่องมือ"
        }
        bytesIn = mapped.sumOf { (it.name.length + it.description.length).toLong() }
        result = OK
        McpServerRecord(
          url = cleanUrl,
          host = host,
          name = serverName,
          description = desc,
          tools = mapped,
          addedUtcMs = nowUtcMs,
        )
      }
    } catch (e: TimeoutCancellationException) {
      result = "$FAILED (หมดเวลา)"
      throw IOException("เชื่อมต่อหมดเวลา (เกิน 15 วินาที)", e)
    } catch (e: CancellationException) {
      result = "หยุดไว้"
      throw e
    } catch (e: Exception) {
      result = "$FAILED (${e.javaClass.simpleName})"
      throw IOException("เชื่อมต่อบริการไม่สำเร็จ (${e.javaClass.simpleName})", e)
    } finally {
      withContext(NonCancellable) {
        runCatching { client.close() }
        log(nowUtcMs, cleanUrl, ConnectionPurpose.MCP_DESCRIBE, bytesIn, null, result)
      }
    }
  }

  override suspend fun call(url: String, tool: String, arguments: Map<String, String>, apiKey: String?): String {
    val uri = validateMcpUrl(url, allowLoopbackHttp)
    val cleanUrl = uri.toString()
    val startedAt = System.currentTimeMillis()
    val bytesOut = arguments.entries.sumOf { (k, v) ->
      (k.encodeToByteArray().size + v.encodeToByteArray().size).toLong()
    }
    val client = newSdkClient()
    var bytesIn: Long? = null
    var result = FAILED
    return try {
      withTimeout(CALL_TIMEOUT_MS.toLong()) {
        client.connect(transport(cleanUrl, apiKey))
        // the name map lives in RAM: after a restart, re-list once so "get_weather" is not called as "get-weather"
        val remoteName = rawToolNames[cleanUrl to tool]
          ?: client.listTools().tools.firstOrNull { sanitizeToolName(it.name) == tool.replace(Regex("-\\d+$"), "") }?.name
            ?.also { rawToolNames[cleanUrl to tool] = it }
          ?: tool
        val jsonArgs = JsonObject(arguments.mapValues { (_, v) -> JsonPrimitive(v) })
        val callResult = client.callTool(
          request = CallToolRequest(CallToolRequestParams(name = remoteName, arguments = jsonArgs)),
        )
        val text = callResult.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }.trim()
        bytesIn = text.encodeToByteArray().size.toLong()
        if (callResult.isError == true) {
          result = "$FAILED (เซิร์ฟเวอร์แจ้งข้อผิดพลาด)"
          throw IOException(text.take(MAX_RESULT_CHARS).ifEmpty { "บริการภายนอกแจ้งข้อผิดพลาด" })
        }
        result = OK
        text.take(MAX_RESULT_CHARS)
      }
    } catch (e: TimeoutCancellationException) {
      result = "$FAILED (หมดเวลา)"
      throw IOException("เรียกใช้บริการหมดเวลา (เกิน 60 วินาที)", e)
    } catch (e: CancellationException) {
      result = "หยุดไว้"
      throw e
    } catch (e: Exception) {
      if (result == FAILED) result = "$FAILED (${e.javaClass.simpleName})"
      throw e
    } finally {
      withContext(NonCancellable) {
        runCatching { client.close() }
        log(startedAt, cleanUrl, ConnectionPurpose.MCP, bytesIn, bytesOut, result)
      }
    }
  }

  private fun transport(url: String, apiKey: String?) = StreamableHttpClientTransport(
    client = httpClient,
    url = url,
    requestBuilder = { apiKey?.let { bearerAuth(it) } },
  )

  private fun newSdkClient(): Client =
    Client(clientInfo = Implementation(name = "haku", version = BuildConfig.VERSION_NAME))

  private suspend fun log(
    atMs: Long,
    url: String,
    purpose: ConnectionPurpose,
    bytesIn: Long?,
    bytesOut: Long?,
    result: String,
  ) {
    try {
      onConnection(ConnectionRecord.of(atMs, url, purpose, bytesIn, bytesOut, result))
    } catch (e: Exception) {
      Log.w(TAG, "connection log write failed: ${e.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "HakuMcp"
    private const val OK = "สำเร็จ"
    private const val FAILED = "ไม่สำเร็จ"
    const val TIMEOUT_MS = 15_000
    /** Tool calls: DeepWiki ask_wiki_question takes ~16 s end to end, past the 15 s describe budget. */
    const val CALL_TIMEOUT_MS = 60_000
    const val MAX_RESULT_CHARS = 2_000

    private val STRING_ISH_TYPES = setOf("string", "number", "integer", "boolean")

    internal fun validateMcpUrl(raw: String, allowLoopbackHttp: Boolean): URI {
      val uri = try {
        URI(raw.trim()).normalize()
      } catch (_: Exception) {
        throw IllegalArgumentException("รูปแบบ URL ไม่ถูกต้อง")
      }
      val scheme = uri.scheme?.lowercase()
      val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("ไม่พบชื่อโฮสต์ใน URL")
      val isHttps = scheme == "https"
      val isDebugLoopback = allowLoopbackHttp && scheme == "http" && host == "127.0.0.1"
      if (!isHttps && !isDebugLoopback) {
        throw IllegalArgumentException("ต้องใช้ https:// เท่านั้น")
      }
      return uri
    }

    internal fun sanitizeToolName(raw: String): String {
      val slug = raw.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .trimEnd('-')
      return slug.ifEmpty { "tool" }
    }

    internal fun mapMcpTool(tool: Tool, host: String): SkillRecord {
      val name = sanitizeToolName(tool.name)
      // the server's human title (MCP `title`, or annotations.title) when it sends one, else the raw name
      val title = (tool.title?.takeIf { it.isNotBlank() } ?: tool.annotations?.title?.takeIf { it.isNotBlank() } ?: tool.name)
        .replace('\r', ' ').replace('\n', ' ').trim().ifEmpty { name }
      val props = tool.inputSchema.properties ?: JsonObject(emptyMap())
      val requiredSet = tool.inputSchema.required?.toSet().orEmpty()
      val stringSlots = props.entries.mapNotNull { (propName, element) ->
        val obj = element as? JsonObject
        val type = obj?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }?.lowercase()
        if (type != null && type !in STRING_ISH_TYPES) return@mapNotNull null
        val slotName = propName.replace(';', '_').replace('=', '_').replace("?", "").trim()
        if (slotName.isEmpty()) return@mapNotNull null
        val slotDesc = obj?.get("description")?.let { (it as? JsonPrimitive)?.contentOrNull }
          ?.replace(';', ',')
          ?.replace('\r', ' ')
          ?.replace('\n', ' ')
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?: slotName
        SkillSlot(name = slotName, description = slotDesc, required = propName in requiredSet)
      }.distinctBy { it.name }

      val readOnly = tool.annotations?.readOnlyHint == true
      val choice = McpToolSlots.choose(stringSlots, props.size, props.keys.count { it in requiredSet }, readOnly)
      val baseDesc = tool.description.orEmpty().replace('\r', ' ').replace('\n', ' ').trim().ifEmpty { title }
      val description = if (choice.tooMany) {
        "${McpServerStore.TOO_MANY_SLOTS_PREFIX}$baseDesc"
      } else {
        baseDesc
      }
      val instructions = baseDesc.take(SkillRecord.MAX_INSTRUCTION_CHARS)
      return SkillRecord(
        name = name,
        title = title,
        description = description,
        instructions = instructions,
        slots = choice.slots,
        action = SkillActions.MCP_ACTION,
        source = SkillSource.MCP,
        enabled = false,
        server = host,
        readOnly = readOnly,
      )
    }
  }
}
