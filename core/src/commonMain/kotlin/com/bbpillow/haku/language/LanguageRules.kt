package com.bbpillow.haku.language

/** Per-language rules (D20). A language with no special rules simply has no entry in [languageRules]. */
interface LanguageRules {
  /** Language-specific normalization, applied after NFC and zero-width removal. */
  fun normalize(text: String): String

  /** Language-specific token expansion after segmentation (e.g. Thai ๆ). */
  fun expandTokens(tokens: List<WordToken>): List<WordToken>
}

// ponytail: adding a language = one rules file + one line here; the pipeline is untouched.
private val languageRules: Map<String, LanguageRules> = mapOf("th" to ThaiRules)

internal fun rulesFor(languageCode: String): LanguageRules? = languageRules[languageCode]
