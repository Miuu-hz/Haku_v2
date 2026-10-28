package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.MemoryExport
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance
import com.bbpillow.haku.network.ConnectionRecord
import com.bbpillow.haku.network.ConnectionRepository
import com.bbpillow.haku.surface.settings.ModelManagerPresenter

/** One remembered fact. [date] is the local short Thai date it was recorded (`23 ก.ย.`). */
data class MemoryFact(val category: String, val id: String, val text: String, val date: String, val provenance: Provenance)

/** One core-memory category with its facts; [title]/[description] are the Thai display names. */
data class MemoryCategoryItem(
  val slug: String,
  val title: String,
  val description: String,
  val count: Int,
  val facts: List<MemoryFact>,
)

/** One night's Dream report: [day] as stored, [title] the Thai date, [text] the Thai report as written by the engine. */
data class DreamReportItem(val day: String, val title: String, val text: String)

/** One row of the connection report (E3): [time] local `29 ต.ค. 14:05`, [received] null when nothing came in. */
data class ConnectionItem(val time: String, val host: String, val purpose: String, val received: String?, val result: String)

/**
 * Everything the Transparency screen draws. [egressToday] = connections today that carried personal data out (the
 * counter). [connections] = every connection in the last 30 days, newest first, with [connectionsTotal] as one line.
 * [dreamReports] = latest nights first (E4).
 */
data class TransparencyUiState(
  val categories: List<MemoryCategoryItem>,
  val egressToday: Int,
  val dreamReports: List<DreamReportItem> = emptyList(),
  val connections: List<ConnectionItem> = emptyList(),
  val connectionsTotal: String = "",
)

/** "What Haku remembers about you" (P3.4, assets E1/E2/E3): core memory by category, forget per fact, connections. Stateless. */
class TransparencyPresenter(
  private val memory: MemoryRepository,
  private val forgetAll: ForgetEverything,
  private val connections: ConnectionRepository,
  private val tzOffsetMinutes: Int,
  private val nowMs: () -> Long,
) {
  fun load(): TransparencyUiState {
    val now = nowMs()
    val log = connections.since(now - ConnectionRepository.RETENTION_MS)
    val today = localDay(now)
    val personal = log.filter { it.purpose.carriesPersonalData }
    return state(
      egressToday = personal.count { localDay(it.atEpochMs) == today },
      connections = log.map(::item),
      connectionsTotal = "30 วันล่าสุด ${log.size} ครั้ง · รับเข้า ${bytes(log.sumOf { it.bytesIn ?: 0L })} · " +
        "ข้อมูลส่วนตัวที่ส่งออก ${bytes(personal.sumOf { it.bytesOut ?: 0L })}",
    )
  }

  private fun state(egressToday: Int, connections: List<ConnectionItem>, connectionsTotal: String) = TransparencyUiState(
    categories = memory.listCategories().map { c ->
      val facts = memory.readCategory(c.slug).map {
        MemoryFact(c.slug, it.id, it.text, ThaiDateFormat.shortDate(MemoryFormat.dayOf(it.atEpochMs + tzOffsetMinutes * 60_000L)), it.provenance)
      }
      val (title, description) = thaiNames[c.slug] ?: (c.slug to c.description)
      MemoryCategoryItem(c.slug, title, description, facts.size, facts)
    },
    egressToday = egressToday,
    dreamReports = memory.listDreamDays().takeLast(MAX_DREAM_REPORTS).reversed().mapNotNull { d ->
      memory.readDreamReport(d)?.let { DreamReportItem(d, ThaiDateFormat.shortDate(d), it.trim()) }
    },
    connections = connections,
    connectionsTotal = connectionsTotal,
  )

  private fun localDay(ms: Long) = MemoryFormat.dayOf(ms + tzOffsetMinutes * 60_000L)

  private fun item(r: ConnectionRecord): ConnectionItem {
    val local = MemoryFormat.formatUtc(r.atEpochMs + tzOffsetMinutes * 60_000L)
    val time = "${ThaiDateFormat.shortDate(local.substring(0, 10))} ${local.substring(11, 16)}"
    return ConnectionItem(time, r.host, r.purpose.thai, r.bytesIn?.takeIf { it > 0 }?.let(::bytes), r.result)
  }

  private fun bytes(n: Long) = if (n <= 0L) "0 ไบต์" else ModelManagerPresenter.formatSize(n)

  fun forget(fact: MemoryFact): TransparencyUiState {
    memory.deleteCoreFact(fact.category, fact.id)
    return load()
  }

  /** Forget one whole category (files + index rows). */
  fun forgetCategory(slug: String): TransparencyUiState {
    memory.deleteCategory(slug)
    return load()
  }

  /** Forget everything Haku captured or wrote; the screen must have asked for a strong confirm first. */
  fun forgetEverything(): TransparencyUiState {
    forgetAll.run()
    return load()
  }

  /** All memory as one readable Markdown text. The Android side writes it where the user picks (SAF). User-initiated, not egress. */
  fun exportMarkdown(): String = MemoryExport.markdown(memory)

  private companion object {
    const val MAX_DREAM_REPORTS = 7

    // the store's descriptions are English prompts for the model; the user reads these
    val thaiNames = mapOf(
      CoreCategories.YOU to ("ตัวคุณ" to "ความชอบและโปรไฟล์"),
      CoreCategories.TOPICS to ("เรื่องที่สนใจ" to "เรื่องที่คุณติดตามหรือใส่ใจ"),
      CoreCategories.AREAS to ("ด้านของชีวิต" to "งาน สุขภาพ ครอบครัว"),
      CoreCategories.PROJECTS to ("โครงการ" to "สิ่งที่คุณกำลังทำอยู่"),
    )
  }
}
