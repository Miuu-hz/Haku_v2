package com.bbpillow.haku.platform.mcp

import android.util.Log
import com.bbpillow.haku.action.ActionHandler
import com.bbpillow.haku.action.ActionResultOutput
import com.bbpillow.haku.action.PermissionLevel
import com.bbpillow.haku.skill.McpClient
import com.bbpillow.haku.skill.McpServerRepository
import com.bbpillow.haku.skill.SkillActions
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ActionHandler] for `mcp.call` ([SkillActions.MCP_ACTION]). Always [PermissionLevel.CONFIRM] so every external
 * MCP tool call shows an ActionGate confirmation card before any data leaves the phone.
 */
class McpActionHandler(
  private val findServerUrl: suspend (String) -> String?,
  private val client: McpClient,
) : ActionHandler {

  constructor(
    servers: McpServerRepository,
    client: McpClient,
  ) : this(
    findServerUrl = { host -> servers.all().firstOrNull { it.host.equals(host, ignoreCase = true) }?.url },
    client = client,
  )

  override val action: String = SkillActions.MCP_ACTION
  override val level: PermissionLevel = PermissionLevel.CONFIRM

  override suspend fun run(arguments: Map<String, String>): ActionResultOutput {
    val host = arguments[SkillActions.KEY_SERVER]?.trim()?.takeIf { it.isNotEmpty() }
    val tool = arguments[SkillActions.KEY_TOOL]?.trim()?.takeIf { it.isNotEmpty() }
    if (host == null || tool == null) {
      return ActionResultOutput(ok = false, message = INVALID_ARGS_MESSAGE)
    }
    val toolArgs = arguments - SkillActions.KEY_SERVER - SkillActions.KEY_TOOL
    val url = findServerUrl(host)
      ?: return ActionResultOutput(ok = false, message = SERVER_NOT_FOUND_MESSAGE)
    return try {
      val result = client.call(url, tool, toolArgs)
      ActionResultOutput(ok = true, message = result)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w("HakuMcp", "call $host/$tool failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
      ActionResultOutput(ok = false, message = FAILED_MESSAGE)
    }
  }

  companion object {
    const val INVALID_ARGS_MESSAGE = "ข้อมูลคำสั่งบริการภายนอกไม่ครบถ้วน"
    const val SERVER_NOT_FOUND_MESSAGE = "ไม่พบบริการภายนอกที่ระบุ"
    const val FAILED_MESSAGE = "เรียกใช้บริการภายนอกไม่สำเร็จ"
  }
}
