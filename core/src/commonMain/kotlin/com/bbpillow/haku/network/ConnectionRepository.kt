package com.bbpillow.haku.network

/** Why Haku opened a connection. [thai] is what the Transparency screen shows; [carriesPersonalData] feeds the egress counter. */
enum class ConnectionPurpose(val thai: String, val carriesPersonalData: Boolean) {
  MODEL_DOWNLOAD("ดาวน์โหลดโมเดล AI ลงเครื่อง", carriesPersonalData = false),
  MCP_DESCRIBE("ตรวจดูเครื่องมือบริการภายนอก (MCP)", carriesPersonalData = false),
  MCP("เรียกบริการภายนอก (MCP)", carriesPersonalData = true),
  PLACE_NAME("ค้นชื่อสถานที่จากพิกัด", carriesPersonalData = true),
}

/**
 * One outbound connection the app itself opened (P3.4 asset E3). Only [host] and [path] are kept, never a query string
 * or user info (signed CDN links carry tokens there): build it with [ConnectionRecord.of]. Bytes null = not known.
 */
data class ConnectionRecord(
  val atEpochMs: Long,
  val host: String,
  val path: String,
  val purpose: ConnectionPurpose,
  val bytesIn: Long?,
  val bytesOut: Long?,
  /** Thai, as the user reads it (`สำเร็จ`, `หยุดไว้`, `ไม่สำเร็จ (HTTP 404)`). */
  val result: String,
) {
  companion object {
    fun of(atEpochMs: Long, url: String, purpose: ConnectionPurpose, bytesIn: Long?, bytesOut: Long?, result: String): ConnectionRecord {
      val (host, path) = hostAndPath(url)
      return ConnectionRecord(atEpochMs, host, path, purpose, bytesIn, bytesOut, result)
    }

    /** `https://user:pw@cdn.hf.co:443/a/b?sig=x#f` -> (`cdn.hf.co:443`, `/a/b`). */
    fun hostAndPath(url: String): Pair<String, String> {
      val noQuery = url.substringAfter("://").substringBefore('?').substringBefore('#')
      val authority = noQuery.substringBefore('/')
      val path = noQuery.drop(authority.length).ifEmpty { "/" }
      return authority.substringAfterLast('@').lowercase() to path
    }
  }
}

/** The connection log shown on the Transparency screen. Kept [RETENTION_MS]; emptied by forget-everything. */
interface ConnectionRepository {
  /** Appends [record] and drops entries older than [RETENTION_MS] before its time. */
  fun add(record: ConnectionRecord)

  /** Entries at or after [fromEpochMs], newest first. */
  fun since(fromEpochMs: Long): List<ConnectionRecord>

  fun clear()

  companion object {
    const val RETENTION_MS = 30 * 86_400_000L
  }
}
