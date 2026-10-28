package com.bbpillow.haku.platform.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.bbpillow.haku.HakuApplication
import com.bbpillow.haku.MainActivity
import com.bbpillow.haku.QuickNoteActivity
import com.bbpillow.haku.R
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.home.CaptureStatus
import com.bbpillow.haku.surface.home.HomeWidget
import com.bbpillow.haku.surface.home.HomeWidgetOutput
import com.bbpillow.haku.surface.note.QuickNoteInput
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * P3.5 home-screen widget: 1–2 lines of the Home brief for this part of the day, what capture is doing, and a
 * "บันทึกด่วน" button. With the D11 lock on it shows only "Haku" + the button (core HomeWidget decides).
 * Redrawn hourly by the system (morning/midday/evening), after nightly/brief runs, on a note save, when the open stay
 * changes and when the app goes to the background.
 */
class HakuWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    val pending = goAsync()
    val app = context.applicationContext
    (app as HakuApplication).graph.scope.launch {
      try {
        update(app)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val TAG = "HakuWidget"

    /** Fire-and-forget [update], for callers that are not suspending. */
    fun refresh(context: Context) {
      val app = context.applicationContext
      (app as HakuApplication).graph.scope.launch { update(app) }
    }

    /** Redraws every placed widget. No widget placed = nothing read. Never throws; a failed read draws the bare widget. */
    suspend fun update(context: Context) {
      val app = context.applicationContext
      val manager = AppWidgetManager.getInstance(app)
      val ids = manager.getAppWidgetIds(ComponentName(app, HakuWidget::class.java))
      if (ids.isEmpty()) return
      val content = try {
        load(app)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "widget load failed: ${e::class.simpleName}") // never the text
        HomeWidget.content(null, lockEnabled = true, CaptureStatus.OFF)
      }
      manager.updateAppWidget(ids, views(app, content))
    }

    private suspend fun load(app: Context): HomeWidgetOutput {
      val graph = (app as HakuApplication).graph
      val now = System.currentTimeMillis()
      val capturing = CaptureController.isEnabled(app)
      return graph.io {
        val p = profile.load()
        // lock on = not even read: nothing of the diary goes into RemoteViews (the launcher's process)
        if (p.lockEnabled || !p.onboarded) return@io HomeWidget.content(null, p.lockEnabled, CaptureStatus.OFF)
        compose(graph.today(now - 86_400_000L)) // the diary exists even if nobody opened the app (as Home does)
        compose(graph.today(now))
        val status = when {
          !capturing -> CaptureStatus.OFF
          CaptureController.recorder(app).openDwell(now) != null -> CaptureStatus.STAYING
          else -> CaptureStatus.MOVING
        }
        HomeWidget.content(home.load(now, p.style), false, status)
      }
    }

    private fun views(context: Context, content: HomeWidgetOutput) = RemoteViews(context.packageName, R.layout.widget_haku).apply {
      setTextViewText(R.id.widget_title, content.title)
      setTextViewText(R.id.widget_body, content.body.orEmpty())
      setViewVisibility(R.id.widget_body, if (content.body == null) View.GONE else View.VISIBLE)
      setTextViewText(R.id.widget_status, content.status.orEmpty())
      setViewVisibility(R.id.widget_status, if (content.status == null) View.GONE else View.VISIBLE)
      // the app opens through its own D11 lock, as from the launcher
      setOnClickPendingIntent(android.R.id.background, activity(context, 0, Intent(context, MainActivity::class.java)))
      setOnClickPendingIntent(R.id.widget_note, activity(context, 1, QuickNoteActivity.intent(context)))
    }

    private fun activity(context: Context, code: Int, intent: Intent) =
      PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** QuickNoteActivity and the notification reply: saves one line as today's user note, then redraws. False = not saved. */
    suspend fun saveNote(context: Context, text: String): Boolean {
      val graph = (context.applicationContext as HakuApplication).graph
      val saved = try {
        graph.io { quickNote.save(QuickNoteInput(text, System.currentTimeMillis())) } != null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "quick note failed: ${e::class.simpleName}")
        false
      }
      if (saved) update(context)
      return saved
    }
  }
}
