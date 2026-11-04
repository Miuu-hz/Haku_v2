package com.bbpillow.haku.surface.settings

/** One credit on the licenses screen. [textAsset] names a file under the app's `assets/licenses/`; null = nothing to open. */
data class OpenSourceNotice(val name: String, val what: String, val license: String, val textAsset: String? = null)

/** Keep true to the build: licenses read from each POM or repository LICENSE, 2026-11-04. A new library adds a line here. */
val OpenSourceNotices: List<OpenSourceNotice> = listOf(
  OpenSourceNotice("Mapsforge", "วาดแผนที่ในการ์ดไทม์ไลน์", "LGPL-3.0", "LGPL-3.0.txt"),
  OpenSourceNotice("ข้อความ GPL-3.0", "ส่วนที่ LGPL-3.0 อ้างถึง", "GPL-3.0", "GPL-3.0.txt"),
  OpenSourceNotice("ข้อมูลแผนที่ OpenStreetMap", "© OpenStreetMap contributors · openstreetmap.org/copyright", "ODbL 1.0"),
  OpenSourceNotice("SQLCipher", "เข้ารหัสฐานข้อมูลในเครื่อง", "BSD-style (Zetetic)", "SQLCipher.txt"),
  OpenSourceNotice("MCP Kotlin SDK", "เชื่อมต่อบริการภายนอก", "Apache-2.0 / MIT", "MCP-Kotlin-SDK.txt"),
  OpenSourceNotice("LiteRT-LM", "รันโมเดล AI ในเครื่อง", "Apache-2.0", "Apache-2.0.txt"),
  OpenSourceNotice("Google AI Edge Gallery", "โค้ดบางส่วนดัดแปลงมาจากโครงการนี้", "Apache-2.0", "Apache-2.0.txt"),
  OpenSourceNotice("Gemma 4 · EmbeddingGemma · Qwen2.5", "โมเดล AI ที่ดาวน์โหลดแยกต่างหาก", "Apache-2.0", "Apache-2.0.txt"),
  OpenSourceNotice("Kotlin · Compose · AndroidX · Ktor · Okio · AndroidSVG", "ส่วนประกอบพื้นฐานของแอป", "Apache-2.0", "Apache-2.0.txt"),
  OpenSourceNotice("Google Play services · Play Billing", "ตำแหน่ง และการซื้อ Haku Plus", "ข้อกำหนดของ Google"),
)
