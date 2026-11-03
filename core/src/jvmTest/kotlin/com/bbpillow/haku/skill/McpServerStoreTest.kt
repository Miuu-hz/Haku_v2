package com.bbpillow.haku.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class McpServerStoreTest {
  private val fs = FakeFileSystem()
  private val root = "/mcp".toPath()
  private val store = McpServerStore(fs, root)

  private val forecastTool = SkillRecord(
    name = "get-forecast",
    title = "พยากรณ์อากาศ",
    description = "ดูพยากรณ์อากาศรายเมือง",
    instructions = "ระบุชื่อเมืองที่ต้องการดูอากาศ",
    slots = listOf(
      SkillSlot("city", "ชื่อเมือง", required = true),
      SkillSlot("days", "จำนวนวัน", required = false),
    ),
    action = SkillActions.MCP_ACTION,
    source = SkillSource.MCP,
    enabled = false,
    server = "weather.example",
  )

  private val pingTool = SkillRecord(
    name = "ping-server",
    title = "ตรวจสถานะ",
    description = "เช็กว่าเซิร์ฟเวอร์ตอบกลับหรือไม่",
    instructions = "",
    slots = emptyList(),
    action = SkillActions.MCP_ACTION,
    source = SkillSource.MCP,
    enabled = false,
    server = "weather.example",
  )

  private val server = McpServerRecord(
    url = "https://weather.example/mcp",
    host = "weather.example",
    name = "Weather Service",
    description = "Tools: get-forecast, ping-server",
    tools = listOf(forecastTool, pingTool),
    addedUtcMs = 1_790_000_000_000L,
  )

  @Test fun roundTripPreservesServerAndTools() {
    store.upsert(server)
    assertEquals(listOf(server), store.all())
  }

  @Test fun removeDeletesServerFile() {
    val other = server.copy(url = "https://other.example/mcp", host = "other.example", addedUtcMs = 1_790_000_000_100L)
    store.upsert(server)
    store.upsert(other)
    store.remove(server.url)
    assertEquals(listOf(other.url), store.all().map { it.url })
  }

  @Test fun toolsDefaultDisabledAndSetToolEnabledPersists() {
    val withEnabledInput = server.copy(
      tools = listOf(forecastTool.copy(enabled = true), pingTool.copy(enabled = true)),
    )
    store.upsert(withEnabledInput)
    assertTrue(store.all().single().tools.all { !it.enabled })

    store.setToolEnabled(server.url, "get-forecast", true)
    val afterEnable = store.all().single().tools
    assertTrue(afterEnable.first { it.name == "get-forecast" }.enabled)
    assertFalse(afterEnable.first { it.name == "ping-server" }.enabled)

    store.setToolEnabled(server.url, "get-forecast", false)
    assertFalse(store.all().single().tools.first { it.name == "get-forecast" }.enabled)
  }

  @Test fun oversizedToolCanNeverBeEnabled() {
    val oversized = forecastTool.copy(
      name = "big-tool",
      description = "${McpServerStore.TOO_MANY_SLOTS_PREFIX}ต้องการข้อมูลเยอะ",
    )
    store.upsert(server.copy(tools = listOf(oversized)))
    store.setToolEnabled(server.url, "big-tool", true)
    assertFalse(store.all().single().tools.single().enabled)
  }
}
