package com.bbpillow.haku.surface.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.surface.components.CapsuleButton
import com.bbpillow.haku.surface.components.HakuIcons
import com.bbpillow.haku.surface.components.SurfaceCard
import com.bbpillow.haku.surface.theme.HakuColors
import com.bbpillow.haku.surface.theme.HakuRadius
import com.bbpillow.haku.task.TaskDraft
import com.bbpillow.haku.task.TaskRules

@Composable
fun TaskEditScreen(
  initial: TaskDraft?,
  todayDay: String,
  tomorrowDay: String,
  onSave: (TaskDraft) -> Unit,
  onDelete: (() -> Unit)?,
  onBack: () -> Unit,
  contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
) {
  var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
  var dueDay by remember(initial) { mutableStateOf(initial?.dueDay) }
  var important by remember(initial) { mutableStateOf(initial?.important ?: false) }
  var showDatePicker by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    TaskEditHeader(isNew = initial == null, onBack = onBack)

    TitleCard(title = title, onTitleChange = { title = it })

    DueDayCard(
      dueDay = dueDay,
      todayDay = todayDay,
      tomorrowDay = tomorrowDay,
      onSelectDay = { dueDay = it },
      onOpenDatePicker = { showDatePicker = true },
    )

    ImportantCard(important = important, onToggle = { important = it })

    ActionButtons(
      title = title,
      dueDay = dueDay,
      important = important,
      onSave = onSave,
      onDelete = onDelete?.let { { showDeleteDialog = true } },
    )
  }

  if (showDatePicker) {
    DueDatePickerDialog(
      initialDay = dueDay,
      onSelectDay = { dueDay = it },
      onDismiss = { showDatePicker = false },
    )
  }

  if (showDeleteDialog && onDelete != null) {
    DeleteConfirmationDialog(
      onConfirm = {
        showDeleteDialog = false
        onDelete()
      },
      onDismiss = { showDeleteDialog = false },
    )
  }
}

@Composable
private fun TaskEditHeader(isNew: Boolean, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(HakuRadius.Pill)
        .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
      contentAlignment = Alignment.Center,
    ) {
      Icon(HakuIcons.Back, contentDescription = "กลับ", tint = HakuColors.Ink, modifier = Modifier.size(20.dp))
    }
    Text(
      text = if (isNew) "เพิ่มงาน" else "แก้งาน",
      style = MaterialTheme.typography.headlineMedium,
      color = HakuColors.Ink,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun TitleCard(title: String, onTitleChange: (String) -> Unit) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = title,
      onValueChange = { onTitleChange(it.take(TaskRules.MAX_TITLE_CHARS)) },
      label = { Text("ชื่องาน") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
      textStyle = MaterialTheme.typography.bodyLarge,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = HakuColors.Ink,
        unfocusedBorderColor = HakuColors.Line,
        focusedLabelColor = HakuColors.Ink,
      ),
    )
  }
}

@Composable
private fun DueDayCard(
  dueDay: String?,
  todayDay: String,
  tomorrowDay: String,
  onSelectDay: (String?) -> Unit,
  onOpenDatePicker: () -> Unit,
) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Text("กำหนดส่ง", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val isCustomDay = dueDay != null && dueDay != todayDay && dueDay != tomorrowDay
      val customLabel = if (isCustomDay) dueDay?.let { ThaiDateFormat.shortDate(it) } ?: "เลือกวัน" else "เลือกวัน"

      DueChip("ไม่มี", selected = dueDay == null, onClick = { onSelectDay(null) }, modifier = Modifier.weight(1f))
      DueChip("วันนี้", selected = dueDay == todayDay, onClick = { onSelectDay(todayDay) }, modifier = Modifier.weight(1f))
      DueChip("พรุ่งนี้", selected = dueDay == tomorrowDay, onClick = { onSelectDay(tomorrowDay) }, modifier = Modifier.weight(1f))
      DueChip(customLabel, selected = isCustomDay, onClick = onOpenDatePicker, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun DueChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .defaultMinSize(minHeight = 44.dp)
      .clip(HakuRadius.Pill)
      .background(if (selected) HakuColors.Ink else HakuColors.Bg)
      .border(1.dp, if (selected) HakuColors.Ink else HakuColors.Line, HakuRadius.Pill)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = if (selected) HakuColors.Surface else HakuColors.Ink,
      maxLines = 1,
    )
  }
}

@Composable
private fun ImportantCard(important: Boolean, onToggle: (Boolean) -> Unit) {
  SurfaceCard(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 48.dp)
        .toggleable(
          value = important,
          role = Role.Switch,
          onValueChange = onToggle,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "สำคัญ",
        style = MaterialTheme.typography.titleSmall,
        color = HakuColors.Ink,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = important,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
          checkedTrackColor = HakuColors.Ink,
          checkedThumbColor = HakuColors.Surface,
          uncheckedTrackColor = HakuColors.Track,
          uncheckedThumbColor = HakuColors.Muted,
          uncheckedBorderColor = HakuColors.Muted,
        ),
      )
    }
    Spacer(Modifier.height(4.dp))
    Text(
      text = "ถ้ากำหนดส่งเหลือไม่ถึง 48 ชม. จะอยู่ในช่องเร่งด่วน",
      style = MaterialTheme.typography.bodySmall,
      color = HakuColors.Muted,
    )
  }
}

@Composable
private fun ActionButtons(
  title: String,
  dueDay: String?,
  important: Boolean,
  onSave: (TaskDraft) -> Unit,
  onDelete: (() -> Unit)?,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val cleanTitle = TaskRules.cleanTitle(title)
    val canSave = cleanTitle != null
    Box(
      modifier = Modifier
        .clip(HakuRadius.Pill)
        .background(if (canSave) HakuColors.Ink else HakuColors.Line)
        .then(
          if (canSave) {
            Modifier.clickable(role = Role.Button) {
              cleanTitle?.let { clean ->
                onSave(TaskDraft(title = clean, dueDay = dueDay, important = important))
              }
            }
          } else Modifier,
        )
        .padding(horizontal = 24.dp, vertical = 13.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "บันทึก",
        style = MaterialTheme.typography.labelLarge,
        color = if (canSave) HakuColors.Surface else HakuColors.Muted,
      )
    }

    if (onDelete != null) {
      CapsuleButton(text = "ลบงาน", onClick = onDelete, outline = true)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePickerDialog(
  initialDay: String?,
  onSelectDay: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val initialMillis = remember(initialDay) {
    initialDay?.let { MemoryFormat.parseUtc("${it}T00:00:00.000Z") }
  }
  val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
  DatePickerDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      CapsuleButton(
        text = "เลือก",
        onClick = {
          datePickerState.selectedDateMillis?.let { millis ->
            onSelectDay(MemoryFormat.dayOf(millis))
          }
          onDismiss()
        },
      )
    },
    dismissButton = {
      CapsuleButton(text = "ยกเลิก", onClick = onDismiss, outline = true)
    },
    shape = HakuRadius.Card,
    containerColor = HakuColors.Surface,
  ) {
    DatePicker(state = datePickerState)
  }
}

@Composable
private fun DeleteConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("ลบงานนี้ไหม", style = MaterialTheme.typography.titleMedium) },
    text = { Text("ลบแล้วจะไม่สามารถกู้คืนได้", style = MaterialTheme.typography.bodyMedium, color = HakuColors.Muted) },
    confirmButton = { CapsuleButton("ลบ", onConfirm) },
    dismissButton = { CapsuleButton("ยกเลิก", onDismiss, outline = true) },
    shape = HakuRadius.Card,
    containerColor = HakuColors.Surface,
  )
}
