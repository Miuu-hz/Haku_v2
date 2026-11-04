package com.bbpillow.haku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bbpillow.haku.action.ActionStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP servers over adb, the same calls the Settings → ความสามารถเสริม screen and the chat card make. Logcat tag `HakuMcp`:
 * `-a com.bbpillow.haku.debug.MCP_ADD --es url https://… [--es key test-value]` describe → store (tools start disabled)
 * `-a com.bbpillow.haku.debug.MCP_LIST` servers and tools as JSON
 * `-a com.bbpillow.haku.debug.MCP_REMOVE --es url https://…`
 * `-a com.bbpillow.haku.debug.MCP_ENABLE --es tool <skill-name> [--ez on false]`
 * `-a com.bbpillow.haku.debug.MCP_CONFIRM [--es id <proposal>]` confirms the newest pending gate card (as its confirm button)
 */
class McpDebugReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val graph = (context.applicationContext as HakuApplication).graph
    // no goAsync: a tool call may outlive the broadcast limit; a DebugJob keeps the process unfrozen
    DebugJob.start(context) {
      try {
        when (intent.action) {
          ACTION_ADD -> {
            val url = intent.getStringExtra("url").orEmpty()
            val t0 = System.currentTimeMillis()
            // --es key: a throwaway value for a test server only, adb arguments are visible to the shell
            val described = graph.addMcpServer(url, intent.getStringExtra("key"))
            Log.i(TAG, "added ${described.host} in ${System.currentTimeMillis() - t0} ms")
            logServers(graph)
          }
          ACTION_LIST -> logServers(graph)
          ACTION_REMOVE -> {
            val url = intent.getStringExtra("url").orEmpty()
            graph.removeMcpServer(url)
            logServers(graph)
          }
          ACTION_ENABLE -> {
            val tool = intent.getStringExtra("tool").orEmpty()
            val on = intent.getBooleanExtra("on", true)
            val url = graph.io { mcpServers.all().firstOrNull { s -> s.tools.any { it.name == tool } }?.url }
            if (url == null) Log.w(TAG, "no tool $tool") else graph.io { mcpServers.setToolEnabled(url, tool, on) }
            logServers(graph)
          }
          ACTION_CONFIRM -> {
            val id = intent.getStringExtra("id")
              ?: graph.actionGate.proposals.value.lastOrNull { it.status == ActionStatus.PENDING }?.id
            if (id == null) {
              Log.w(TAG, "no pending proposal")
              return@start
            }
            val t0 = System.currentTimeMillis()
            val r = graph.confirmAction(id)
            Log.i(TAG, JSONObject().put("id", id).put("ms", System.currentTimeMillis() - t0).put("status", r?.status?.name)
              .put("action", r?.request?.action).put("message", r?.message).toString())
            // the chat's own summary step (MainShell onConfirmAction); --es q = the question the bubble belonged to
            if (r != null && r.request.action == com.bbpillow.haku.skill.SkillActions.MCP_ACTION && r.status == ActionStatus.DONE) {
              val t1 = System.currentTimeMillis()
              val text = summarizeMcpResult(graph, intent.getStringExtra("q").orEmpty(), r)
              Log.i(TAG, JSONObject().put("summaryMs", System.currentTimeMillis() - t1).put("bubble", text).toString())
            }
          }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "${intent.action} failed", t)
      }
    }
  }

  private suspend fun logServers(graph: AppGraph) {
    val servers = graph.io { mcpServers.all() }
    val json = JSONArray(servers.map { s ->
      JSONObject().put("url", s.url).put("host", s.host).put("name", s.name).put("tools", JSONArray(s.tools.map { t ->
        JSONObject().put("name", t.name).put("title", t.title).put("enabled", t.enabled).put("readOnly", t.readOnly).put("server", t.server)
          .put("slots", JSONArray(t.slots.map { "${it.name}${if (it.required) "*" else ""}" })).put("description", t.description)
      }))
    })
    Log.i(TAG, json.toString())
  }

  private companion object {
    const val TAG = "HakuMcp"
    const val ACTION_ADD = "com.bbpillow.haku.debug.MCP_ADD"
    const val ACTION_LIST = "com.bbpillow.haku.debug.MCP_LIST"
    const val ACTION_REMOVE = "com.bbpillow.haku.debug.MCP_REMOVE"
    const val ACTION_ENABLE = "com.bbpillow.haku.debug.MCP_ENABLE"
    const val ACTION_CONFIRM = "com.bbpillow.haku.debug.MCP_CONFIRM"
  }
}
