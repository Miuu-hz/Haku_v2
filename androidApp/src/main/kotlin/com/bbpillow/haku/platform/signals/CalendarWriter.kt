package com.bbpillow.haku.platform.signals

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import com.bbpillow.haku.capture.CalendarRowInput
import com.bbpillow.haku.memory.MemoryFormat
import java.time.ZoneId

/**
 * Android adapter for calendar writes and conflict checks.
 * One-tap writes from chat appointment cards go through here.
 */
object CalendarWriter {
  private const val TAG = "CalendarWriter"

  private val CALENDAR_PROJECTION = arrayOf(
    CalendarContract.Calendars._ID,
    CalendarContract.Calendars.IS_PRIMARY,
    CalendarContract.Calendars.ACCOUNT_TYPE,
  )

  private val INSTANCES_PROJECTION = arrayOf(
    CalendarContract.Instances.EVENT_ID,
    CalendarContract.Instances.BEGIN,
    CalendarContract.Instances.END,
    CalendarContract.Instances.TITLE,
    CalendarContract.Instances.ALL_DAY,
    CalendarContract.Instances.STATUS,
    CalendarContract.Instances.SELF_ATTENDEE_STATUS,
  )

  fun canWrite(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

  /** Blocking; call on IO. Event id, or null when no writable calendar / denied / failed. */
  fun insert(context: Context, title: String, startUtcMs: Long, endUtcMs: Long): Long? {
    if (!canWrite(context)) return null
    return try {
      val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
      val cursor = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        CALENDAR_PROJECTION,
        selection,
        null,
        null,
      ) ?: return null

      // ponytail: collect candidates, pick first primary, then google account, else the first writable
      val candidates = cursor.use { c ->
        buildList {
          while (c.moveToNext()) {
            val id = c.getLong(0)
            val isPrimary = !c.isNull(1) && c.getInt(1) == 1
            val isGoogle = c.getString(2) == "com.google"
            add(CalendarCandidate(id, isPrimary, isGoogle))
          }
        }
      }

      val calendarId = candidates.firstOrNull { it.isPrimary }?.id
        ?: candidates.firstOrNull { it.isGoogle }?.id
        ?: candidates.firstOrNull()?.id
        ?: return null

      val eventValues = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, startUtcMs)
        put(CalendarContract.Events.DTEND, endUtcMs)
        put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        put(CalendarContract.Events.HAS_ALARM, 1)
      }

      val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
      val eventId = eventUri?.lastPathSegment?.toLongOrNull() ?: return null

      // ponytail: 15-minute alert reminder; don't fail event creation if reminder fails
      val reminderValues = ContentValues().apply {
        put(CalendarContract.Reminders.EVENT_ID, eventId)
        put(CalendarContract.Reminders.MINUTES, 15)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
      }
      try {
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to insert reminder for event", e)
      }

      eventId
    } catch (e: SecurityException) {
      Log.e(TAG, "Missing calendar permission to insert event", e)
      null
    } catch (e: Exception) {
      Log.e(TAG, "Failed to insert calendar event", e)
      null
    }
  }

  /** Blocking; call on IO. Overlapping events (not all-day, not cancelled, not declined); empty without READ_CALENDAR. */
  fun conflicts(context: Context, startUtcMs: Long, endUtcMs: Long): List<CalendarRowInput> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return emptyList()
    }
    if (startUtcMs > endUtcMs) return emptyList()
    return try {
      val cursor = CalendarContract.Instances.query(
        context.contentResolver,
        INSTANCES_PROJECTION,
        startUtcMs,
        endUtcMs,
      ) ?: return emptyList()

      cursor.use { c ->
        buildList {
          while (c.moveToNext()) {
            val allDay = c.getInt(4) != 0
            val canceled = c.getInt(5) == CalendarContract.Events.STATUS_CANCELED
            val declined = c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
            val begin = c.getLong(1)
            val end = c.getLong(2)
            if (!allDay && !canceled && !declined && begin < endUtcMs && end > startUtcMs) {
              add(
                CalendarRowInput(
                  eventId = c.getLong(0),
                  beginUtcMs = begin,
                  endUtcMs = end,
                  title = c.getString(3),
                  allDay = allDay,
                  canceled = canceled,
                  declined = declined,
                ),
              )
            }
          }
        }
      }
    } catch (e: SecurityException) {
      Log.e(TAG, "Missing calendar permission to query conflicts", e)
      emptyList()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to query calendar conflicts", e)
      emptyList()
    }
  }

  /** Blocking; call on IO. The card / heads-up line for the first overlap, e.g. ชนกับ “ประชุม” 10:00–11:00; null = free. */
  fun conflictLine(context: Context, startUtcMs: Long, endUtcMs: Long, tzOffsetMinutes: Int): String? {
    val first = conflicts(context, startUtcMs, endUtcMs).firstOrNull() ?: return null
    fun hhmm(utcMs: Long) = MemoryFormat.formatUtc(utcMs + tzOffsetMinutes * 60_000L).substring(11, 16)
    return "ชนกับ “${first.title.orEmpty()}” ${hhmm(first.beginUtcMs)}–${hhmm(first.endUtcMs)}"
  }

  private data class CalendarCandidate(
    val id: Long,
    val isPrimary: Boolean,
    val isGoogle: Boolean,
  )
}
