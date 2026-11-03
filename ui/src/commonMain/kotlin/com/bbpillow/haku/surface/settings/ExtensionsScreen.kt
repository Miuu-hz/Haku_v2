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
// customtasks/agentchat/AddMcpServerFromUrlDialog.kt and AddMcpDisclaimerDialog.kt.
// State-hoisted into Compose Multiplatform with Thai copy, mandatory PDPA cross-border egress disclaimer
// before every server add, per-tool opt-in switches (<= 3 slots rule), and Haku One UI theme tokens.

package com.bbpillow.haku.surface.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bbpillow.haku.skill.McpServerRecord
import com.bbpillow.haku.skill.McpServerStore
import com.bbpillow.haku.skill.SkillRecord
import com.bbpillow.haku.skill.SkillSource
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.ConfirmDialog
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.IconTile
import com.bbpillow.haku.surface.components.ScreenTitle
import com.bbpillow.haku.surface.components.SectionLabel
import com.bbpillow.haku.surface.components.StatChip
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.surface.theme.HakuTone

private enum class AddStep { NONE, DISCLAIMER, URL_FORM }

/**
 * Extensions screen ("ความสามารถเสริม") opened from Settings.
 * Lists on-device skills ("ทักษะ") and remote MCP servers ("บริการภายนอก (MCP)"). State-hoisted.
 */
@Composable
fun ExtensionsScreen(
  skills: List<SkillRecord>,
  servers: List<McpServerRecord>,
  onBack: () -> Unit,
  onToggleTool: (url: String, tool: String, enabled: Boolean) -> Unit,
  onRemoveServer: (url: String) -> Unit,
  onConnectServer: (url: String) -> Unit,
  modifier: Modifier = Modifier,
  adding: Boolean = false,
  addError: String? = null,
  addedServer: McpServerRecord? = null,
  onDismissAdd: () -> Unit = {},
  onToggleSkill: (name: String, enabled: Boolean) -> Unit = { _, _ -> },
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var expandedUrls by remember { mutableStateOf(setOf<String>()) }
  var deletingServer by remember { mutableStateOf<McpServerRecord?>(null) }
  var addStep by remember { mutableStateOf(AddStep.NONE) }
  var urlInput by remember { mutableStateOf("") }

  Column(
    modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      Modifier
        .size(36.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }

    ScreenTitle("ความสามารถเสริม", eyebrow = "ทักษะในเครื่องและบริการภายนอก (MCP)")

    SectionLabel("ทักษะ")
    SurfaceCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
      if (skills.isEmpty()) {
        Text(
          "ยังไม่มีทักษะในเครื่อง",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
          modifier = Modifier.padding(vertical = 10.dp),
        )
      }
      skills.forEachIndexed { index, skill ->
        val isBuiltIn = skill.source == SkillSource.BUILT_IN
        Row(
          Modifier.fillMaxWidth().padding(vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Column(Modifier.weight(1f)) {
            Text("${skill.title} · /${skill.name}", style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
            Text(skill.description, style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
            if (isBuiltIn) {
              Text("เปิดอยู่เสมอ", style = MaterialTheme.typography.labelSmall, color = HakuColors.Muted, modifier = Modifier.padding(top = 2.dp))
            }
          }
          // ponytail: built-ins always on for now, show switch disabled with "เปิดอยู่เสมอ"
          Switch(
            checked = if (isBuiltIn) true else skill.enabled,
            onCheckedChange = if (isBuiltIn) null else ({ onToggleSkill(skill.name, it) }),
            enabled = !isBuiltIn,
            colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
          )
        }
        if (index < skills.lastIndex) HorizontalDivider(color = HakuColors.Line)
      }
    }

    SectionLabel("บริการภายนอก (MCP)", Modifier.padding(top = 4.dp))
    if (servers.isEmpty()) {
      SurfaceCard(Modifier.fillMaxWidth()) {
        Text(
          "ยังไม่มีบริการภายนอก กด “+ เพิ่มบริการ” เพื่อเชื่อมต่อเซิร์ฟเวอร์ MCP",
          style = MaterialTheme.typography.bodySmall,
          color = HakuColors.Muted,
        )
      }
    }

    servers.forEach { server ->
      val expanded = server.url in expandedUrls
      SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            Modifier
              .fillMaxWidth()
              .clip(HakuRadius.Small)
              .clickable(role = Role.Button, onClickLabel = "ดูเครื่องมือของ ${server.host}") {
                expandedUrls = if (expanded) expandedUrls - server.url else expandedUrls + server.url
              },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            IconTile(HakuIcons.Spark, HakuTone.Blue, size = 40.dp)
            Column(Modifier.weight(1f)) {
              Text(server.name.ifBlank { server.host }, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
              Text(
                "@${server.host} · ${server.tools.size} เครื่องมือ",
                style = MaterialTheme.typography.bodySmall,
                color = HakuColors.Muted,
              )
            }
            Text(
              "ลบ",
              style = MaterialTheme.typography.labelLarge,
              color = HakuColors.Coral,
              modifier = Modifier
                .clip(HakuRadius.Pill)
                .clickable(role = Role.Button, onClickLabel = "ลบ ${server.host}") { deletingServer = server }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Icon(HakuIcons.Chevron, contentDescription = null, tint = HakuColors.Muted, modifier = Modifier.size(18.dp))
          }

          if (expanded) {
            HorizontalDivider(color = HakuColors.Line)
            if (server.tools.isEmpty()) {
              Text("เซิร์ฟเวอร์นี้ไม่มีเครื่องมือให้เรียกใช้", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
            }
            server.tools.forEachIndexed { idx, tool ->
              val tooManySlots = McpServerStore.isTooManySlots(tool.description)
              Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Column(Modifier.weight(1f)) {
                  Text(
                    tool.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tooManySlots) HakuColors.Muted else HakuColors.Ink,
                  )
                  Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooManySlots) HakuColors.Coral else HakuColors.Muted,
                  )
                }
                Switch(
                  checked = tool.enabled && !tooManySlots,
                  onCheckedChange = { enabled ->
                    if (!tooManySlots) onToggleTool(server.url, tool.name, enabled)
                  },
                  enabled = !tooManySlots,
                  colors = SwitchDefaults.colors(checkedTrackColor = HakuColors.Ink, checkedThumbColor = HakuColors.Surface),
                )
              }
              if (idx < server.tools.lastIndex) HorizontalDivider(color = HakuColors.Line)
            }
          }
        }
      }
    }

    CapsuleButton(
      text = "+ เพิ่มบริการ",
      onClick = {
        onDismissAdd()
        urlInput = ""
        addStep = AddStep.DISCLAIMER
      },
      modifier = Modifier.fillMaxWidth(),
      outline = true,
    )
  }

  deletingServer?.let { server ->
    ConfirmDialog(
      title = "ลบบริการ \"${server.name.ifBlank { server.host }}\"?",
      message = "บริการ @${server.host} และเครื่องมือทั้งหมดของบริการนี้จะถูกลบออกจากเครื่องทันที",
      confirmText = "ลบ",
      onConfirm = {
        expandedUrls = expandedUrls - server.url
        onRemoveServer(server.url)
        deletingServer = null
      },
      onDismiss = { deletingServer = null },
    )
  }

  if (addStep == AddStep.DISCLAIMER) {
    ConfirmDialog(
      title = "ก่อนเพิ่มบริการภายนอก",
      message = "ข้อมูลที่คุณส่งให้บริการภายนอกจะออกจากโทรศัพท์เครื่องนี้ไปยังโฮสต์ปลายทาง ซึ่งอาจอยู่นอกประเทศไทย " +
        "Haku จะแสดงข้อมูลและถามให้คุณยืนยันทุกครั้งก่อนส่ง โปรดเพิ่มเฉพาะบริการที่คุณไว้ใจเท่านั้น",
      confirmText = "เข้าใจแล้ว",
      onConfirm = { addStep = AddStep.URL_FORM },
      onDismiss = { addStep = AddStep.NONE },
    )
  }

  if (addStep == AddStep.URL_FORM) {
    AddMcpServerDialog(
      url = urlInput,
      onUrlChange = { urlInput = it },
      adding = adding,
      error = addError,
      addedServer = addedServer,
      onConnect = {
        val trimmed = urlInput.trim()
        if (trimmed.isNotEmpty() && !adding) onConnectServer(trimmed)
      },
      onDismiss = {
        if (!adding) {
          addStep = AddStep.NONE
          onDismissAdd()
        }
      },
    )
  }
}

@Composable
private fun AddMcpServerDialog(
  url: String,
  onUrlChange: (String) -> Unit,
  adding: Boolean,
  error: String?,
  addedServer: McpServerRecord?,
  onConnect: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (addedServer != null) "เชื่อมต่อสำเร็จ" else "เพิ่มบริการภายนอก (MCP)",
        style = MaterialTheme.typography.titleMedium,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (addedServer == null) {
          OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL ของบริการ (https://)") },
            singleLine = true,
            enabled = !adding,
            textStyle = MaterialTheme.typography.bodyMedium,
          )
          if (adding) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = HakuColors.Ink,
              )
              Text("กำลังเชื่อมต่อและดึงรายการเครื่องมือ…", style = MaterialTheme.typography.bodySmall, color = HakuColors.Muted)
            }
          }
          error?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = HakuColors.Coral)
          }
        } else {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(addedServer.name.ifBlank { addedServer.host }, style = MaterialTheme.typography.titleSmall, color = HakuColors.Ink)
            StatChip("@${addedServer.host}")
          }
          Text(
            "พบ ${addedServer.tools.size} เครื่องมือ (เริ่มต้นปิดไว้ทุกตัว เปิดใช้ได้ในรายการบริการ):",
            style = MaterialTheme.typography.bodySmall,
            color = HakuColors.Muted,
          )
          addedServer.tools.forEach { tool ->
            Text(
              "• ${tool.title}: ${tool.description}",
              style = MaterialTheme.typography.bodySmall,
              color = if (McpServerStore.isTooManySlots(tool.description)) HakuColors.Coral else HakuColors.Ink,
            )
          }
        }
      }
    },
    confirmButton = {
      if (addedServer != null) {
        CapsuleButton("เสร็จสิ้น", onDismiss)
      } else if (!adding) {
        CapsuleButton("เชื่อมต่อ", onConnect)
      }
    },
    dismissButton = {
      if (addedServer == null && !adding) {
        CapsuleButton("ยกเลิก", onDismiss, outline = true)
      }
    },
    containerColor = HakuColors.Surface,
    shape = HakuRadius.Card,
  )
}
