package com.bbpillow.haku.surface.memory

import com.bbpillow.haku.language.ThaiDateFormat
import com.bbpillow.haku.memory.CoreCategories
import com.bbpillow.haku.memory.MemoryExport
import com.bbpillow.haku.memory.MemoryFormat
import com.bbpillow.haku.memory.MemoryRepository
import com.bbpillow.haku.memory.Provenance

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

/** Everything the Transparency screen draws. [egressToday] = network calls that left the device today. */
data class TransparencyUiState(val categories: List<MemoryCategoryItem>, val egressToday: Int)

/** "What Haku remembers about you" (P3.4, asset E1/E2): core memory by category, forget per fact. Stateless. */
class TransparencyPresenter(
  private val memory: MemoryRepository,
  private val forgetAll: ForgetEverything,
  private val tzOffsetMinutes: Int,
) {
  fun load(): TransparencyUiState = TransparencyUiState(
    categories = memory.listCategories().map { c ->
      val facts = memory.readCategory(c.slug).map {
        MemoryFact(c.slug, it.id, it.text, ThaiDateFormat.shortDate(MemoryFormat.dayOf(it.atEpochMs + tzOffsetMinutes * 60_000L)), it.provenance)
      }
      val (title, description) = thaiNames[c.slug] ?: (c.slug to c.description)
      MemoryCategoryItem(c.slug, title, description, facts.size, facts)
    },
    // ponytail: no network layer exists yet (P4), so nothing can leave the device; read its egress log here then
    egressToday = 0,
  )

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
    // the store's descriptions are English prompts for the model; the user reads these
    val thaiNames = mapOf(
      CoreCategories.YOU to ("ตัวคุณ" to "ความชอบและโปรไฟล์"),
      CoreCategories.TOPICS to ("เรื่องที่สนใจ" to "เรื่องที่คุณติดตามหรือใส่ใจ"),
      CoreCategories.AREAS to ("ด้านของชีวิต" to "งาน สุขภาพ ครอบครัว"),
      CoreCategories.PROJECTS to ("โครงการ" to "สิ่งที่คุณกำลังทำอยู่"),
    )
  }
}
