package com.bbpillow.haku.memory.dream

import com.bbpillow.haku.memory.MemoryRepository

/**
 * What Dream remembers between nights, kept as a plain-text file in the dreams level under a reserved day
 * (the repository has no other per-level slot). One line per theme: `P key category factId` (promoted),
 * `F key` (user deleted the promoted fact: never again), `R key` (user rejected the theme).
 */
internal class DreamLedger(private val memory: MemoryRepository) {
  private data class Promoted(val category: String, val factId: String)

  private val promoted = linkedMapOf<String, Promoted>()
  private val forgotten = linkedSetOf<String>()
  private val rejected = linkedSetOf<String>()

  init {
    memory.readDreamReport(LEDGER_DAY)?.lines()?.forEach { line ->
      val p = line.split('\t')
      when {
        p[0] == "P" && p.size == 4 -> promoted[p[1]] = Promoted(p[2], p[3])
        p[0] == "F" && p.size == 2 -> forgotten += p[1]
        p[0] == "R" && p.size == 2 -> rejected += p[1]
      }
    }
  }

  fun isForgotten(key: String) = key in forgotten
  fun isRejected(key: String) = key in rejected
  fun isPromoted(key: String) = key in promoted

  fun reject(key: String) {
    rejected += key
    save()
  }

  fun recordPromoted(key: String, category: String, factId: String) {
    promoted[key] = Promoted(category, factId)
    save()
  }

  /** A promoted fact that is gone from its category was deleted by the user: remember that for good. */
  fun syncForgotten() {
    val gone = promoted.filter { (_, p) -> memory.readCategory(p.category).none { it.id == p.factId } }.keys.toList()
    if (gone.isEmpty()) return
    gone.forEach { promoted.remove(it); forgotten += it }
    save()
  }

  private fun save() {
    val lines = promoted.map { (k, p) -> "P\t$k\t${p.category}\t${p.factId}" } + forgotten.map { "F\t$it" } + rejected.map { "R\t$it" }
    memory.writeDreamReport(LEDGER_DAY, lines.joinToString("\n"))
  }

  companion object {
    const val LEDGER_DAY = "0000-00-00"
  }
}
