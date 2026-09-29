package com.bbpillow.haku.language

/** Hand-written synonym groups for query expansion (D19, no model). Terms are compared in normalized form. */
class SynonymTable(groups: List<List<String>>) {
  private val byTerm: Map<String, Set<String>> = groups
    .flatMap { g -> g.map { it to g.toSet() } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, sets) -> sets.flatten().toSet() }

  val terms: Set<String> get() = byTerm.keys

  /** The term itself plus all its synonyms; just the term when unknown. */
  fun expand(term: String): Set<String> = byTerm[term] ?: setOf(term)
}
