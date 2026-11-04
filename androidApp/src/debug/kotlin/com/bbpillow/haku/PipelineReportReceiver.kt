package com.bbpillow.haku

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.bbpillow.haku.capture.CaptureQueryInput
import com.bbpillow.haku.capture.CaptureSource
import com.bbpillow.haku.capture.PlaceNames
import com.bbpillow.haku.platform.capture.CaptureController
import com.bbpillow.haku.surface.onboarding.ActiveModel
import com.bbpillow.haku.surface.onboarding.ModelChoice
import com.bbpillow.haku.surface.timeline.TimelineMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Read-only pipeline status probe over adb (docs/decisions.md).
 * Logs ONE JSON line to logcat tag `HakuPipeline`:
 * adb shell am broadcast -n com.bbpillow.haku/.PipelineReportReceiver -a com.bbpillow.haku.debug.PIPELINE_REPORT
 */
class PipelineReportReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION) return
    val app = context.applicationContext as HakuApplication
    val pending = goAsync()
    app.graph.scope.launch {
      try {
        report(app)
      } catch (t: Throwable) {
        Log.e(TAG, "pipeline report failed", t)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun report(app: HakuApplication) {
    val root = JSONObject()

    root.put("profile", safeSection {
      val p = app.graph.io { profile.load() }
      val modelFile = runCatching {
        when (val m = p.model) {
          is ActiveModel.Catalog -> app.graph.downloader.file(m.choice)
          is ActiveModel.Imported -> app.graph.importer.file(m.fileName)
          ActiveModel.None -> null
        }
      }.getOrNull()
      val modelFilePresent = modelFile?.exists() == true && modelFile.length() > 0L
      val embeddingPath = app.graph.embeddingModelPath()
      JSONObject().apply {
        put("onboarded", p.onboarded)
        put("chosen_model", p.model.encode())
        put("model_file_present", modelFilePresent)
        put("model_file_size", if (modelFilePresent) modelFile!!.length() else 0L)
        put("embedding_file_present", embeddingPath != null)
      }
    })

    root.put("permissions", safeSection {
      val listeners = NotificationManagerCompat.getEnabledListenerPackages(app)
      JSONObject().apply {
        put("location_fine", app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        put("location_background", app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        put("activity_recognition", app.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
        put("calendar_read", app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        put("calendar_write", app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        put("post_notifications", app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        put("notification_listener", listeners.contains(app.packageName))
      }
    })

    root.put("capture", safeSection {
      val enabled = CaptureController.isEnabled(app)
      val now = System.currentTimeMillis()
      val (total, last24h, known) = app.graph.io {
        Triple(
          events.count(),
          events.query(CaptureQueryInput(now - 86_400_000L, now)).size,
          places.list(),
        )
      }
      JSONObject().apply {
        put("enabled", enabled)
        put("events_total", total)
        put("events_last_24h", last24h)
        put("places_count", known.size)
        // counts only, never the names: they are the user's places
        put("places_confirmed", known.count { it.confirmed })
        put("places_auto_named", known.count { it.autoName != null })
        put("places_fallback_named", known.count { it.autoName?.let(PlaceNames::isFallback) == true })
      }
    })

    root.put("diary", safeSection {
      val (daysCount, newest) = app.graph.io {
        val days = memory.listDays()
        val newest3 = days.takeLast(3).reversed().associateWith { memory.readDay(it).size }
        Pair(days.size, newest3)
      }
      val newestObj = JSONObject()
      newest.forEach { (d, c) -> newestObj.put(d, c) }
      JSONObject().apply {
        put("days_count", daysCount)
        put("newest_3_days", newestObj)
      }
    })

    // why the Timeline map shows or not, per local day. Counts only: never names or coordinates (they are the user's places)
    root.put("timeline_map", safeSection {
      val now = System.currentTimeMillis()
      val tz = app.graph.tzOffsetMinutes
      val days = JSONObject()
      app.graph.io {
        (0 until MAP_PROBE_DAYS).map { app.graph.today(now - it * 86_400_000L) }.forEach { day ->
          val rowTimes = diary.readDay(day, tz).map { it.atEpochMs }.toSet()
          val seen = diary.placesOfDay(day, tz)
          val byId = seen.places.associateBy { it.id }
          val matched = seen.visits.filter { it.startUtcMs in rowTimes || it.endUtcMs in rowTimes }
          days.put(day, JSONObject().apply {
            put("rows", rowTimes.size)
            put("stays_seen", seen.visits.size)
            put("stays_matched", matched.size)
            put("distinct_places", matched.mapNotNull { byId[it.placeId] }.map { it.lat to it.lon }.distinct().size)
            put("map_shown", TimelineMap.build(rowTimes, seen) != null)
          })
        }
      }
      val recent = app.graph.io { events.query(CaptureQueryInput(now - 7 * 86_400_000L, now)) }
      val lastDwell = recent
        .filter { it.source == CaptureSource.DwellStart || it.source == CaptureSource.DwellEnd }
        .maxByOrNull { it.timeUtcMs }
      val sourcesSinceDwell = JSONObject()
      lastDwell?.let { d ->
        recent.filter { it.timeUtcMs > d.timeUtcMs }.groupingBy { it.source::class.simpleName.orEmpty() }.eachCount() // type only: a calendar source holds its title
          .forEach { (source, count) -> sourcesSinceDwell.put(source, count) }
      }
      JSONObject().apply {
        put("days", days)
        put("events_since_last_dwell", sourcesSinceDwell)
        put("open_stay", lastDwell?.source == CaptureSource.DwellStart)
        put("last_dwell_hours_ago", lastDwell?.let { (now - it.timeUtcMs) / 3_600_000L } ?: JSONObject.NULL)
      }
    })

    // ponytail: SearchIndexStore is private in AppGraph.Data; no public read API
    root.put("search_index", safeSection {
      JSONObject().apply {
        put("total_rows", "n/a")
      }
    })

    root.put("vectors", safeSection {
      val modelId = ModelChoice.EMBEDDING_GEMMA_2.name
      val count = app.graph.io { vectors.count(modelId) }
      JSONObject().apply {
        put("model_id", modelId)
        put("count", count)
        // ponytail: VectorIndexer.pending() mutates/prunes vectors in DB; no read-only API
        put("missing_vectors", "n/a")
      }
    })

    root.put("core_memory", safeSection {
      val (cats, dreamDaysCount, newestDream) = app.graph.io {
        val categories = memory.listCategories().associate { it.slug to memory.readCategory(it.slug).size }
        val dreams = memory.listDreamDays()
        Triple(categories, dreams.size, dreams.lastOrNull())
      }
      val catsObj = JSONObject()
      cats.forEach { (slug, count) -> catsObj.put(slug, count) }
      JSONObject().apply {
        put("categories", catsObj)
        put("dream_days_count", dreamDaysCount)
        put("newest_dream_day", newestDream ?: JSONObject.NULL)
      }
    })

    root.put("evening_summaries", safeSection {
      val now = System.currentTimeMillis()
      val last3 = (0..2).map { app.graph.today(now - it * 86_400_000L) }
      val presentMap = app.graph.io {
        last3.associateWith { summaries.find(it) != null }
      }
      val obj = JSONObject()
      presentMap.forEach { (d, present) -> obj.put(d, present) }
      obj
    })

    root.put("nightly_worker", safeSection {
      val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork("haku-nightly").get(5, TimeUnit.SECONDS)
      val info = infos.firstOrNull()
      JSONObject().apply {
        put("state", info?.state?.name ?: "none")
        put("run_attempt_count", info?.runAttemptCount ?: 0)
        val nextSchedule = info?.nextScheduleTimeMillis?.takeIf { it > 0L && it != Long.MAX_VALUE }
        put("next_schedule_ms", nextSchedule ?: JSONObject.NULL)
      }
    })

    root.put("warm", safeSection {
      app.graph.warmth.isWarm
    })

    Log.i(TAG, root.toString())
  }

  private suspend inline fun safeSection(crossinline block: suspend () -> Any): Any =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      "error:${t::class.java.simpleName}"
    }

  companion object {
    const val ACTION = "com.bbpillow.haku.debug.PIPELINE_REPORT"
    const val TAG = "HakuPipeline"
    private const val MAP_PROBE_DAYS = 7
  }
}
