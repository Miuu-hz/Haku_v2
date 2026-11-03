package com.bbpillow.haku.platform.signals

import android.content.Context
import com.bbpillow.haku.action.ActionHandler
import com.bbpillow.haku.action.ActionResultOutput
import com.bbpillow.haku.action.CalendarAddArgs
import com.bbpillow.haku.action.PermissionLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P4.1 [ActionHandler] for `calendar.add`. Always [PermissionLevel.CONFIRM], wrapping [CalendarWriter].
 */
class CalendarActionHandler(
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActionHandler {
  override val action: String = CalendarAddArgs.ACTION
  override val level: PermissionLevel = PermissionLevel.CONFIRM

  override suspend fun run(arguments: Map<String, String>): ActionResultOutput {
    val parsed = CalendarAddArgs.parse(arguments)
      ?: return ActionResultOutput(ok = false, message = INVALID_ARGS_MESSAGE)
    if (!CalendarWriter.canWrite(context)) {
      return ActionResultOutput(ok = false, message = NEEDS_PERMISSION_MESSAGE, needsPermission = true)
    }
    val eventId = withContext(ioDispatcher) {
      CalendarWriter.insert(context, parsed.title, parsed.startUtcMs, parsed.endUtcMs, parsed.allDay)
    }
    if (eventId == null) {
      return if (!CalendarWriter.canWrite(context)) {
        ActionResultOutput(ok = false, message = NEEDS_PERMISSION_MESSAGE, needsPermission = true)
      } else {
        ActionResultOutput(ok = false, message = FAILED_MESSAGE)
      }
    }
    return ActionResultOutput(ok = true, message = DONE_MESSAGE)
  }

  companion object {
    const val DONE_MESSAGE = "เพิ่มลงปฏิทินแล้ว · เตือนก่อน 15 นาที"
    const val NEEDS_PERMISSION_MESSAGE = "ต้องอนุญาตให้ Haku เขียนปฏิทินก่อน"
    const val FAILED_MESSAGE = "เพิ่มไม่สำเร็จ"
    const val INVALID_ARGS_MESSAGE = "ข้อมูลนัดหมายไม่ครบถ้วน"
  }
}
