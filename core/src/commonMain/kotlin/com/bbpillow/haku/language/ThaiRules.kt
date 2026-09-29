package com.bbpillow.haku.language

/** ALL Thai-specific text rules live in this file (D20). Marks are written as \u escapes so they stay visible. */
internal object ThaiRules : LanguageRules {
  private const val MAI_YAMOK = "ๆ"
  private const val TONES = "[่-๋]"

  override fun normalize(text: String): String =
    composeSaraAm(reorderMarks(joinSaraAe(joinMaiYamok(thaiDigitsToArabic(text)))))

  override fun expandTokens(tokens: List<WordToken>): List<WordToken> = expandMaiYamok(tokens)

  private fun thaiDigitsToArabic(text: String): String =
    text.map { if (it in '๐'..'๙') '0' + (it - '๐') else it }.joinToString("")

  // "เร็ว ๆ" -> "เร็วๆ"
  private fun joinMaiYamok(text: String): String = text.replace(Regex("\\s+$MAI_YAMOK"), MAI_YAMOK)

  // two sara e typed for sara ae
  private fun joinSaraAe(text: String): String = text.replace("เเ", "แ")

  // Run after reorderMarks. [tone] + nikhahit + sara aa -> [tone] + sara am; sara am + tone -> tone + sara am.
  private fun composeSaraAm(text: String): String =
    text.replace(Regex("($TONES?)ํา"), "$1ำ")
      .replace(Regex("ำ($TONES)"), "$1ำ")

  // Canonical order after a consonant: vowel marks, then tone mark, then thanthakhat/nikhahit.
  private fun markRank(c: Char): Int = when (c) {
    'ั', in 'ิ'..'ฺ', '็' -> 1
    in '่'..'๋' -> 2
    in '์'..'๎' -> 3
    else -> 0
  }

  private fun reorderMarks(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      if (markRank(text[i]) == 0) { out.append(text[i++]); continue }
      var j = i
      while (j < text.length && markRank(text[j]) != 0) j++
      out.append(text.substring(i, j).toList().sortedBy(::markRank).joinToString(""))
      i = j
    }
    return out.toString()
  }

  // "เร็วๆ" -> เร็ว, เร็ว ; a lone "ๆ" token repeats the previous word.
  private fun expandMaiYamok(tokens: List<WordToken>): List<WordToken> {
    val out = mutableListOf<WordToken>()
    for (t in tokens) {
      val base = t.text.removeSuffix(MAI_YAMOK)
      when {
        base == t.text -> out += t
        base.isEmpty() -> out.lastOrNull()?.let { out += it.copy(start = t.start, end = t.end) }
        else -> { out += t.copy(text = base); out += t.copy(text = base) }
      }
    }
    return out
  }
}
