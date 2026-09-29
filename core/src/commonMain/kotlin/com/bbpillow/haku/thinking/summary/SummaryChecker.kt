package com.bbpillow.haku.thinking.summary

import com.bbpillow.haku.capture.diary.EndingParticle

/** Pure output check (D14/D15, plan 03 section 6.3). Any reason means: do not show the model text. */
object SummaryChecker {
  const val MIN_CHARS = 30
  const val MAX_CHARS = 500
  private const val MIN_THAI_RATIO = 0.7

  private val time = Regex("""\d{1,2}[:.]\d{2}""")
  private val number = Regex("""\d+""")
  private val latinWord = Regex("""[A-Za-z]+""")
  private val quoted = Regex("[“\"]([^”\"]+)[”\"]")
  private val hidden = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B-\\u200F\\u2028-\\u202E\\u2060\\uFEFF]")
  private val thaiNumberPhrase =
    Regex("(หนึ่ง|สอง|สาม|สี่|ห้า|หก|เจ็ด|แปด|เก้า|สิบ|ร้อย) ?(ชั่วโมง|นาที|โมง|ทุ่ม|กิโลเมตร|ครั้ง|แห่ง|คน)")
  private val allParticles = listOf("ครับ", "ค่ะ", "คะ")
  private val placeCues = listOf("ออกจาก", "ถึง", "ไปที่", "ไปยัง", "แวะที่", "เดินทางไป")
  private val cueContinuations = listOf("เวลา", "ช่วง", "ตอน", "จุดหมาย", "ที่หมาย", "แล้ว", "กัน")
  private val placeNouns = listOf(
    "ร้านอาหาร", "ร้านกาแฟ", "คาเฟ่", "โรงพยาบาล", "ห้าง", "สนามบิน", "โรงเรียน", "มหาวิทยาลัย", "ตลาด", "โรงแรม",
    "สวนสาธารณะ", "ฟิตเนส", "โรงหนัง", "ธนาคาร", "ร้านสะดวกซื้อ", "สถานี", "วัด", "บริษัท", "ออฟฟิศ",
  )

  fun check(input: SummaryCheckInput): SummaryCheckOutput {
    val text = input.text.trim()
    val facts = input.factLines.joinToString("\n")
    val reasons = buildList {
      if (text.length < MIN_CHARS) add("too-short")
      if (text.length > MAX_CHARS) add("too-long")
      if (hidden.containsMatchIn(text)) add("control-chars")
      if (thaiRatio(text) < MIN_THAI_RATIO) add("not-thai")
      addAll(invented(text, facts))
      addAll(particle(text, facts, input.style.particle))
    }
    return SummaryCheckOutput(reasons.distinct())
  }

  private fun invented(text: String, facts: String): List<String> = buildList {
    val ft = arabic(facts)
    val t = arabic(text)
    val knownTimes = time.findAll(ft).map { hhmm(it.value) }.toSet()
    if (time.findAll(t).any { hhmm(it.value) !in knownTimes }) add("invented-time")
    val knownNumbers = number.findAll(ft).map { it.value.trimStart('0') }.toSet()
    val spokenNumber = thaiNumberPhrase.findAll(text).any { it.value.replace(" ", "") !in facts.replace(" ", "") }
    if (number.findAll(t).any { it.value.trimStart('0') !in knownNumbers } || spokenNumber) add("invented-number")
    val knownLatin = latinWord.findAll(facts).map { it.value.lowercase() }.toSet()
    if (latinWord.findAll(text).any { it.value.lowercase() !in knownLatin }) add("latin-word")
    if (quoted.findAll(text).any { it.groupValues[1] !in facts }) add("invented-quote")
    if (placeNouns.any { it in text && it !in facts } || cueNamesUnknownPlace(text, facts)) add("invented-place")
  }

  /** After a place cue the text must continue with a place that appears in the facts (or a known non-place word). */
  private fun cueNamesUnknownPlace(text: String, facts: String): Boolean {
    val known = knownPlaces(facts)
    return placeCues.any { cue ->
      var from = text.indexOf(cue)
      var bad = false
      while (from >= 0 && !bad) {
        val rest = text.substring(from + cue.length).trimStart(' ', '“', '"')
        bad = rest.isNotEmpty() && known.none { rest.startsWith(it) } && cueContinuations.none { rest.startsWith(it) }
        from = text.indexOf(cue, from + cue.length)
      }
      bad
    }
  }

  /** Place names as DiaryTemplates wrote them (after ออกจาก / ถึง, up to a duration or line end), plus quoted titles. */
  internal fun knownPlaces(facts: String): Set<String> = buildSet {
    Regex("(?:ออกจาก|ถึง)(.+?)(?: ใช้เวลา| อยู่|$)", RegexOption.MULTILINE).findAll(facts)
      .forEach { add(stripParticle(it.groupValues[1])) }
    toList().forEach { add(it.removePrefix("ที่")) } // "ไปที่ทำงาน" reads as cue ไปที่ + ทำงาน
    quoted.findAll(facts).forEach { add(it.groupValues[1]) }
  }.filter { it.isNotBlank() }.toSet()

  private fun particle(text: String, facts: String, want: EndingParticle): List<String> {
    val stray = allParticles.any { it != want.text && it in text && it !in facts }
    if (want == EndingParticle.NONE) return if (stray) listOf("unexpected-particle") else emptyList()
    return buildList {
      if (!text.trimEnd(' ', '.', '!', '\n', '…').endsWith(want.text)) add("missing-particle")
      if (stray) add("wrong-particle")
    }
  }

  private fun stripParticle(s: String) = allParticles.fold(s) { acc, p -> acc.removeSuffix(p) }.trim()

  private fun hhmm(s: String): String {
    val (h, m) = s.split(':', '.')
    return "${h.toInt()}:$m"
  }

  private fun thaiRatio(text: String): Double {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return 0.0
    return letters.count { it in '฀'..'๿' }.toDouble() / letters.length
  }

  private fun arabic(s: String) = s.map { if (it in '๐'..'๙') '0' + (it - '๐') else it }.joinToString("")
}
