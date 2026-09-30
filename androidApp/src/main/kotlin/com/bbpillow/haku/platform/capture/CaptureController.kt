package com.bbpillow.haku.platform.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.sqlite.SQLiteConnection
import com.bbpillow.haku.BuildConfig
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bbpillow.haku.capture.CaptureEvent
import com.bbpillow.haku.capture.CaptureEventStore
import com.bbpillow.haku.capture.CaptureRecorder
import com.bbpillow.haku.capture.DwellDetectOutput
import com.bbpillow.haku.capture.DwellDetector
import com.bbpillow.haku.capture.EventPlace
import com.bbpillow.haku.platform.signals.CalendarReader
import com.bbpillow.haku.platform.signals.DeviceSignalReceiver
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level switch for P2.2/P2.5 capture. Nothing runs until [start] is called explicitly (end of onboarding, or the
 * debug receiver, see docs/permissions.md). No GPS stream: Activity Recognition transitions and one geofence around the
 * open stay wake the app, and each wake-up takes at most one balanced-power fix.
 */
object CaptureController {
  const val TAG = "HakuCapture"
  private const val PREFS = "capture"
  private const val KEY_ENABLED = "enabled"
  private const val GEOFENCE_ID = "open-dwell"
  private const val REQUEST_ACTIVITY = 1
  private const val REQUEST_GEOFENCE = 2
  /** Wake-ups run inside goAsync (about 10 s before the system gives up on the broadcast), so the fix gets less. */
  private const val FIX_TIMEOUT_MS = 8_000L
  private const val FIX_MAX_AGE_MS = 60_000L

  /** Every DB touch runs here, one at a time (the store is not thread-safe). Receivers hand work over with [runAsync]. */
  private val io = Executors.newSingleThreadExecutor()

  /** The same single thread as a coroutine dispatcher: the app shell's stores share [connection] and must queue here too. */
  val dispatcher: CoroutineDispatcher = io.asCoroutineDispatcher()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private var connection: SQLiteConnection? = null
  @Volatile private var loggedNoBackground = false
  private var recorder: CaptureRecorder? = null

  fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

  /** Turns capture on and (re)registers everything. Also called after boot / app update. Missing permissions are skipped and logged. */
  fun start(context: Context) {
    val app = context.applicationContext
    prefs(app).edit().putBoolean(KEY_ENABLED, true).apply()
    requestActivityTransitions(app)
    DeviceSignalReceiver.register(app)
    io.execute {
      runCatching {
        val now = System.currentTimeMillis()
        recorder(app).openDwell(now)?.place?.let { fence(app, it) }
        catchUp(app, now)
      }.onFailure { Log.e(TAG, "start catch-up failed", it) }
    }
    Log.i(TAG, "capture started")
  }

  fun stop(context: Context) {
    val app = context.applicationContext
    prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
    runCatching { ActivityRecognition.getClient(app).removeActivityTransitionUpdates(wakeIntent(app, REQUEST_ACTIVITY)) }
    LocationServices.getGeofencingClient(app).removeGeofences(listOf(GEOFENCE_ID))
    DeviceSignalReceiver.unregister(app)
    Log.i(TAG, "capture stopped")
  }

  /** Process start (Application.onCreate): re-attach the live signal receiver if capture is on. Cheap no-op otherwise. */
  fun resumeIfEnabled(context: Context) {
    if (isEnabled(context)) DeviceSignalReceiver.register(context.applicationContext)
  }

  /**
   * Runs [block] on the capture thread with the receiver kept alive until it finishes. The thread is free while [block]
   * is suspended (e.g. in [takeFix]), so other DB work is not held up by a wait on Play services.
   */
  fun runAsync(receiver: BroadcastReceiver, context: Context, block: suspend (CaptureRecorder) -> Unit) {
    val pending = receiver.goAsync()
    val app = context.applicationContext
    scope.launch {
      try {
        block(recorder(app))
      } catch (t: Throwable) {
        Log.e(TAG, "capture work failed", t)
      } finally {
        pending.finish()
      }
    }
  }

  /**
   * The app's capture database. Opened once (synchronized, so a racing first call cannot open a second connection);
   * every use of it must still run on [dispatcher]. ponytail: plain SQLite for now; D11 encryption wiring comes with onboarding.
   */
  @Synchronized fun connection(context: Context): SQLiteConnection = connection
    ?: BundledSQLiteDriver().open(File(context.noBackupFilesDir, "haku.db").path).also { connection = it }

  @Synchronized fun recorder(context: Context): CaptureRecorder =
    recorder ?: CaptureRecorder(CaptureEventStore(connection(context))).also { recorder = it }

  /** Keep the geofence on the open stay: fence a new one, drop it when the stay ended. */
  fun syncGeofence(context: Context, before: CaptureEvent?, after: DwellDetectOutput) {
    if (after.openDwell?.id == before?.id) return
    val place = after.openDwell?.place
    if (place != null) fence(context, place) else LocationServices.getGeofencingClient(context).removeGeofences(listOf(GEOFENCE_ID))
  }

  /** Things that need no wake-up of their own: charging state the live receiver missed, and today's calendar. */
  fun catchUp(context: Context, nowUtcMs: Long) {
    val rec = recorder(context)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    battery?.let { rec.recordCharging(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, nowUtcMs, confidence = 0.5) }
    CalendarReader.window(nowUtcMs).let { (from, to) ->
      rec.recordCalendar(CalendarReader.read(context, nowUtcMs), from, to)
    }
  }

  /**
   * One balanced-power (Wi-Fi/cell, ~100 m) fix, reusing a cached one up to a minute old, at most [FIX_TIMEOUT_MS].
   * Waits on an IO thread, not the capture thread.
   */
  @SuppressLint("MissingPermission")
  suspend fun takeFix(context: Context): EventPlace? {
    if (!has(context, Manifest.permission.ACCESS_FINE_LOCATION) && !has(context, Manifest.permission.ACCESS_COARSE_LOCATION)) return null
    val request = CurrentLocationRequest.Builder()
      .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
      .setMaxUpdateAgeMillis(FIX_MAX_AGE_MS)
      .setDurationMillis(FIX_TIMEOUT_MS)
      .build()
    val cancel = CancellationTokenSource()
    val task = LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, cancel.token)
    return withContext(Dispatchers.IO) {
      runCatching { Tasks.await(task, FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        .onFailure { Log.w(TAG, "no fix", it) }
        .also { cancel.cancel() } // stop the request on timeout; no-op once it completed
        .getOrNull()
    }?.let { EventPlace(it.latitude, it.longitude) }
  }

  fun wakeIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
    context,
    requestCode,
    Intent(context, LocationWakeReceiver::class.java),
    // Mutable: Play services fills in the transition / geofence extras.
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )

  @SuppressLint("MissingPermission")
  private fun requestActivityTransitions(context: Context) {
    if (!has(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
      Log.w(TAG, "ACTIVITY_RECOGNITION not granted, no wake-ups")
      return
    }
    // ponytail: ENTER of each activity + EXIT of STILL only; every extra transition is an extra wake-up (battery).
    val enters = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE)
      .map { ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build() }
    val stillExit = ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
    ActivityRecognition.getClient(context)
      .requestActivityTransitionUpdates(ActivityTransitionRequest(enters + stillExit), wakeIntent(context, REQUEST_ACTIVITY))
      .addOnFailureListener { Log.e(TAG, "activity transitions not registered", it) }
  }

  @SuppressLint("MissingPermission")
  private fun fence(context: Context, place: EventPlace) {
    if (!has(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
      Log.w(TAG, "ACCESS_FINE_LOCATION not granted, no geofence")
      return
    }
    // Geofence transitions need "allow all the time"; without it addGeofences fails, so leave it to activity transitions
    if (!has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
      if (BuildConfig.DEBUG && !loggedNoBackground) Log.i(TAG, "ACCESS_BACKGROUND_LOCATION not granted, no geofence; activity transitions only")
      loggedNoBackground = true
      return
    }
    val geofence = Geofence.Builder()
      .setRequestId(GEOFENCE_ID)
      .setCircularRegion(place.lat, place.lon, DwellDetector.SAME_PLACE_RADIUS_M.toFloat())
      .setExpirationDuration(Geofence.NEVER_EXPIRE)
      .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
      .build()
    val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
    LocationServices.getGeofencingClient(context).addGeofences(request, wakeIntent(context, REQUEST_GEOFENCE))
      .addOnFailureListener { Log.e(TAG, "geofence not added", it) }
  }

  private fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
