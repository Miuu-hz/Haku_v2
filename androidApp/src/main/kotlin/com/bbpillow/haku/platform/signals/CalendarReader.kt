package com.bbpillow.haku.platform.signals

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.bbpillow.haku.capture.CalendarRowInput
import java.time.Instant
import java.time.ZoneId

/** Read-only view of the device calendar, local yesterday..tomorrow. Filtering (all-day, cancelled...) is core's job. */
object CalendarReader {
  private val PROJECTION = arrayOf(
    CalendarContract.Instances.EVENT_ID,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.STATUS,
    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
  )

  /** Yesterday through tomorrow in local time; also the replace window for rescheduled/cancelled events. */
  fun window(nowUtcMs: Long): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowUtcMs).atZone(zone).toLocalDate()
    return today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() to
      today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
  }

  fun read(context: Context, nowUtcMs: Long): List<CalendarRowInput> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val (from, to) = window(nowUtcMs)
    val cursor = CalendarContract.Instances.query(context.contentResolver, PROJECTION, from, to) ?: return emptyList()
    return cursor.use { c ->
      buildList {
        while (c.moveToNext()) {
          add(
            CalendarRowInput(
              eventId = c.getLong(0),
              beginUtcMs = c.getLong(1),
              endUtcMs = c.getLong(2),
              title = c.getString(3),
              allDay = c.getInt(4) != 0,
              canceled = c.getInt(5) == CalendarContract.Events.STATUS_CANCELED,
              declined = c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            ),
          )
        }
      }
    }
  }
}
