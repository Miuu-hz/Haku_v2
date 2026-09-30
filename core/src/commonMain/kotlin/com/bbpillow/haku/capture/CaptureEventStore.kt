package com.bbpillow.haku.capture

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

/**
 * All capture-event SQL. Table `events` (indexed by time and by type+time) and `deleted_ids` (tombstones).
 * Not thread-safe: callers serialize access. Encryption at rest (D11) comes from the driver the app opens
 * [db] with, not from this class.
 */
class CaptureEventStore(private val db: SQLiteConnection) : CaptureEventRepository {
  init {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, time_utc_ms INTEGER NOT NULL, end_utc_ms INTEGER, " +
        "type TEXT NOT NULL, sub TEXT NOT NULL, lat REAL, lon REAL, place_id TEXT, confidence REAL NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS events_time ON events(time_utc_ms)")
    db.execSQL("CREATE INDEX IF NOT EXISTS events_type_time ON events(type, time_utc_ms)")
    db.execSQL("CREATE TABLE IF NOT EXISTS deleted_ids(id TEXT PRIMARY KEY)")
  }

  override fun append(event: CaptureEvent): Boolean = inTransaction { insert(event) }

  override fun appendAll(events: List<CaptureEvent>): Int = inTransaction { events.count(::insert) }

  override fun query(input: CaptureQueryInput): List<CaptureEvent> {
    val types = input.types
    if (types != null && types.isEmpty()) return emptyList()
    val typeClause = types?.joinToString(",", " AND type IN (", ")") { "?" } ?: ""
    val sql = "SELECT id, time_utc_ms, end_utc_ms, type, sub, lat, lon, place_id, confidence FROM events " +
      "WHERE time_utc_ms >= ? AND time_utc_ms < ?$typeClause ORDER BY time_utc_ms, id"
    return db.prepare(sql).use { st ->
      st.bindLong(1, input.fromUtcMs)
      st.bindLong(2, input.toUtcMs)
      types?.forEachIndexed { i, t -> st.bindText(i + 3, t) }
      buildList { while (st.step()) add(readEvent(st)) }
    }
  }

  override fun replaceCalendar(fromUtcMs: Long, toUtcMs: Long, events: List<CaptureEvent>): Int = inTransaction {
    val before = query(CaptureQueryInput(fromUtcMs, toUtcMs, setOf(CaptureTypes.CALENDAR))).toSet()
    run("DELETE FROM events WHERE type = ? AND time_utc_ms >= ? AND time_utc_ms < ?") {
      it.bindText(1, CaptureTypes.CALENDAR)
      it.bindLong(2, fromUtcMs)
      it.bindLong(3, toUtcMs)
    }
    events.count { insert(it) && it !in before }
  }

  override fun count(): Int = scalar("SELECT count(*) FROM events").toInt()

  override fun delete(id: String): Boolean = inTransaction {
    run("INSERT OR IGNORE INTO deleted_ids(id) VALUES(?)") { it.bindText(1, id) }
    run("DELETE FROM events WHERE id = ?") { it.bindText(1, id) }
    scalar("SELECT changes()") > 0
  }

  override fun purgeBefore(cutoffUtcMs: Long): Int = inTransaction {
    run("DELETE FROM events WHERE time_utc_ms < ?") { it.bindLong(1, cutoffUtcMs) }
    scalar("SELECT changes()").toInt()
  }

  private fun insert(e: CaptureEvent): Boolean {
    val tombstoned = db.prepare("SELECT 1 FROM deleted_ids WHERE id = ?").use { it.bindText(1, e.id); it.step() }
    if (tombstoned) return false
    val (type, sub) = encode(e.source)
    run(
      "INSERT OR IGNORE INTO events(id, time_utc_ms, end_utc_ms, type, sub, lat, lon, place_id, confidence) " +
        "VALUES(?,?,?,?,?,?,?,?,?)",
    ) {
      it.bindText(1, e.id)
      it.bindLong(2, e.timeUtcMs)
      if (e.endUtcMs == null) it.bindNull(3) else it.bindLong(3, e.endUtcMs)
      it.bindText(4, type)
      it.bindText(5, sub)
      if (e.place == null) {
        it.bindNull(6)
        it.bindNull(7)
        it.bindNull(8)
      } else {
        it.bindDouble(6, e.place.lat)
        it.bindDouble(7, e.place.lon)
        if (e.place.placeId == null) it.bindNull(8) else it.bindText(8, e.place.placeId)
      }
      it.bindDouble(9, e.confidence)
    }
    return scalar("SELECT changes()") > 0
  }

  private fun readEvent(st: SQLiteStatement): CaptureEvent {
    val place = if (st.isNull(5)) null else EventPlace(st.getDouble(5), st.getDouble(6), if (st.isNull(7)) null else st.getText(7))
    return CaptureEvent(
      id = st.getText(0),
      timeUtcMs = st.getLong(1),
      source = decode(st.getText(3), st.getText(4)),
      place = place,
      endUtcMs = if (st.isNull(2)) null else st.getLong(2),
      confidence = st.getDouble(8),
    )
  }

  private fun encode(s: CaptureSource): Pair<String, String> = when (s) {
    CaptureSource.DwellStart -> CaptureTypes.DWELL_START to ""
    CaptureSource.DwellEnd -> CaptureTypes.DWELL_END to ""
    is CaptureSource.ActivityTransition -> CaptureTypes.ACTIVITY to "${s.activity.name}:${if (s.entering) "ENTER" else "EXIT"}"
    is CaptureSource.DeviceSignal -> CaptureTypes.DEVICE to s.signal.name
    is CaptureSource.CalendarEntry -> CaptureTypes.CALENDAR to s.title
  }

  private fun decode(type: String, sub: String): CaptureSource = when (type) {
    CaptureTypes.DWELL_START -> CaptureSource.DwellStart
    CaptureTypes.DWELL_END -> CaptureSource.DwellEnd
    CaptureTypes.ACTIVITY -> sub.split(":").let { CaptureSource.ActivityTransition(ActivityKind.valueOf(it[0]), it[1] == "ENTER") }
    CaptureTypes.DEVICE -> CaptureSource.DeviceSignal(DeviceSignalKind.valueOf(sub))
    CaptureTypes.CALENDAR -> CaptureSource.CalendarEntry(sub)
    else -> error("unknown capture type: $type")
  }

  private fun scalar(sql: String): Long = db.prepare(sql).use { it.step(); it.getLong(0) }

  private fun run(sql: String, bind: (SQLiteStatement) -> Unit) = db.prepare(sql).use { bind(it); it.step() }

  private fun <T> inTransaction(block: () -> T): T {
    db.execSQL("BEGIN")
    try {
      return block().also { db.execSQL("COMMIT") }
    } catch (e: Throwable) {
      db.execSQL("ROLLBACK")
      throw e
    }
  }
}
