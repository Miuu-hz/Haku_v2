package com.bbpillow.haku.thinking.appointment

import com.bbpillow.haku.memory.MemoryFormat

data class AppointmentDraft(val title: String, val startUtcMs: Long, val endUtcMs: Long)

object AppointmentParser {
  private const val HOUR_MS = 3_600_000L
  private const val DAY_MS = 86_400_000L

  private val CUES = listOf("มีนัด", "นัด", "ประชุม", "เจอ", "ไปหา", "คอล", "สัมภาษณ์")
  private val QUESTIONS = listOf("ไหม", "มั้ย", "อะไร", "เมื่อไหร่", "เมื่อไร", "กี่โมง", "ที่ไหน", "บ้าง", "หรือเปล่า", "?")

  private val THAI_NUMS = mapOf(
    "หนึ่ง" to 1, "นึง" to 1, "สอง" to 2, "สาม" to 3, "สี่" to 4, "ห้า" to 5,
    "หก" to 6, "เจ็ด" to 7, "แปด" to 8, "เก้า" to 9, "สิบ" to 10, "สิบเอ็ด" to 11, "สิบสอง" to 12,
  )

  private val WEEKDAYS = mapOf(
    "อาทิตย์" to 0, "จันทร์" to 1, "อังคาร" to 2, "พุธ" to 3,
    "พฤหัสบดี" to 4, "พฤหัส" to 4, "ศุกร์" to 5, "เสาร์" to 6,
  )

  private val MONTHS = mapOf(
    "มกราคม" to 1, "ม.ค." to 1, "กุมภาพันธ์" to 2, "ก.พ." to 2,
    "มีนาคม" to 3, "มี.ค." to 3, "เมษายน" to 4, "เม.ย." to 4,
    "พฤษภาคม" to 5, "พ.ค." to 5, "มิถุนายน" to 6, "มิ.ย." to 6,
    "กรกฎาคม" to 7, "ก.ค." to 7, "สิงหาคม" to 8, "ส.ค." to 8,
    "กันยายน" to 9, "ก.ย." to 9, "ตุลาคม" to 10, "ต.ค." to 10,
    "พฤศจิกายน" to 11, "พ.ย." to 11, "ธันวาคม" to 12, "ธ.ค." to 12,
  )

  fun parse(text: String, nowUtcMs: Long, tzOffsetMinutes: Int): AppointmentDraft? {
    val norm = thaiDigits(text)
    if (norm.trim().startsWith("ถ้า") || "ทุกครั้งที่" in norm || "ให้เตือน" in norm) return null
    if (QUESTIONS.any { it in norm }) return null
    if (CUES.none { it in norm }) return null

    val (time, timeRange) = findTime(norm) ?: return null
    val (hour, minute) = time

    val offsetMs = tzOffsetMinutes * 60_000L
    val nowLocalMs = nowUtcMs + offsetMs
    val nowLocalDay = nowLocalMs.floorDiv(DAY_MS)
    val todayWd = (nowLocalDay + 4).mod(7).toInt() // 0=Sun..6=Sat

    val iso = MemoryFormat.formatUtc(nowLocalMs)
    val curYear = iso.substring(0, 4).toInt()
    val curMonth = iso.substring(5, 7).toInt()
    val curDay = iso.substring(8, 10).toInt()
    val curHour = iso.substring(11, 13).toInt()
    val curMinute = iso.substring(14, 16).toInt()

    val isTimeAhead = (hour * 60 + minute) > (curHour * 60 + curMinute)
    val (targetEpochDay, dateRange) = findDate(norm, nowLocalDay, todayWd, curYear, curMonth, curDay, isTimeAhead)

    val startLocalMs = targetEpochDay * DAY_MS + hour * HOUR_MS + minute * 60_000L
    val startUtcMs = startLocalMs - offsetMs
    val endUtcMs = startUtcMs + HOUR_MS // ponytail: brief specifies end = start + 1 hour

    val title = cleanTitle(text, timeRange, dateRange)
    return AppointmentDraft(title, startUtcMs, endUtcMs)
  }

  // ponytail: 1:1 char replacement keeps string length and indices identical to original text
  private fun thaiDigits(s: String): String =
    s.map { if (it in '๐'..'๙') '0' + (it - '๐') else it }.joinToString("")

  private fun parseMinute(raw: String?, min1: String?, min2: String?): Int {
    if (raw == "ครึ่ง") return 30
    val digits = min1?.takeIf { it.isNotEmpty() } ?: min2?.takeIf { it.isNotEmpty() }
    return digits?.toIntOrNull() ?: 0
  }

  private fun findTime(text: String): Pair<Pair<Int, Int>, IntRange>? =
    parseNoon(text) ?: parseBai(text) ?: parseEvening(text) ?: parseThum(text) ?:
    parseDtee(text) ?: parseMong(text) ?: parseDigital(text)

  private fun parseNoon(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*(เที่ยงคืน|เที่ยง(?:วัน)?)(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val hour = if (m.groupValues[1] == "เที่ยงคืน") 0 else 12
    return (hour to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseBai(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*บ่าย\s*(?:([1-4]|หนึ่ง|สอง|สาม|สี่)\s*)?(?:โมง)?(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val numStr = m.groupValues[1].takeIf { it.isNotEmpty() }
    val hasMong = "โมง" in m.value
    val minStr = m.groupValues[2].takeIf { it.isNotEmpty() }
    if (numStr == null && !hasMong && minStr == null) return null
    val n = numStr?.let { THAI_NUMS[it] ?: it.toIntOrNull() } ?: 1
    return (12 + n to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseEvening(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*([3-6]|สาม|สี่|ห้า|หก)\s*โมงเย็น(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val n = THAI_NUMS[m.groupValues[1]] ?: m.groupValues[1].toInt()
    return (12 + n to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseThum(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*([1-5]|หนึ่ง|นึง|สอง|สาม|สี่|ห้า)?\s*ทุ่ม(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val n = m.groupValues[1].takeIf { it.isNotEmpty() }?.let { THAI_NUMS[it] ?: it.toIntOrNull() } ?: 1
    return (18 + n to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseDtee(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*ตี\s*([1-5]|หนึ่ง|สอง|สาม|สี่|ห้า)(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val n = THAI_NUMS[m.groupValues[1]] ?: m.groupValues[1].toInt()
    return (n to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseMong(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*(\d{1,2}|สิบเอ็ด|สิบสอง|สิบ|เก้า|แปด|เจ็ด|หก)\s*โมง(?:\s*เช้า)?(?:\s*(ครึ่ง|ตรง|(\d{1,2})\s*นาที|(\d{1,2})))?""").find(text) ?: return null
    val n = THAI_NUMS[m.groupValues[1]] ?: m.groupValues[1].toInt()
    if (n !in 6..12) return null
    return (n to parseMinute(m.groupValues[2], m.groupValues[3], m.groupValues[4])) to m.range
  }

  private fun parseDigital(text: String): Pair<Pair<Int, Int>, IntRange>? {
    val m = Regex("""(?:เวลา|ตอน|ช่วง)?\s*(?:([01]?\d|2[0-3])[:.]([0-5]\d)(?:\s*น\.?)?|([01]?\d|2[0-3])\s*น\.)""").find(text) ?: return null
    val hour = (m.groupValues[1].takeIf { it.isNotEmpty() } ?: m.groupValues[3]).toInt()
    val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
    return (hour to minute) to m.range
  }

  private fun findDate(
    text: String, nowLocalDay: Long, todayWd: Int, curYear: Int, curMonth: Int, curDay: Int, isTimeAhead: Boolean,
  ): Pair<Long, IntRange?> {
    parseRelativeDate(text, nowLocalDay)?.let { return it.first to it.second }
    parseWeekdayDate(text, nowLocalDay, todayWd, isTimeAhead)?.let { return it.first to it.second }
    parseThaiMonthDate(text, curYear, curMonth, curDay, isTimeAhead)?.let { return it.first to it.second }
    parseSlashDate(text, curYear, curMonth, curDay, isTimeAhead)?.let { return it.first to it.second }
    parseBareDayDate(text, curYear, curMonth, curDay, isTimeAhead)?.let { return it.first to it.second }
    val targetDay = if (isTimeAhead) nowLocalDay else nowLocalDay + 1L
    return targetDay to null
  }

  private fun parseRelativeDate(text: String, nowLocalDay: Long): Pair<Long, IntRange>? {
    val m = Regex("""(?:ในวัน|ใน|วัน)?(มะรืนนี้|มะรืน|พรุ่งนี้|วันนี้)""").find(text) ?: return null
    val offset = when (m.groupValues[1]) {
      "มะรืนนี้", "มะรืน" -> 2L
      "พรุ่งนี้" -> 1L
      else -> 0L
    }
    return (nowLocalDay + offset) to m.range
  }

  private fun parseWeekdayDate(
    text: String, nowLocalDay: Long, todayWd: Int, isTimeAhead: Boolean,
  ): Pair<Long, IntRange>? {
    val m = Regex("""(?:ในวัน|ใน|วัน)?(พฤหัสบดี|พฤหัส|อาทิตย์|จันทร์|อังคาร|พุธ|ศุกร์|เสาร์)(นี้|หน้า)?""").find(text) ?: return null
    val targetWd = WEEKDAYS[m.groupValues[1]] ?: return null
    val isNextWeek = m.groupValues[2] == "หน้า"
    val diff = if (targetWd == todayWd) {
      if (isNextWeek) (if (isTimeAhead) 7L else 14L)
      else (if (isTimeAhead) 0L else 7L)
    } else {
      val nextDiff = (targetWd - todayWd).mod(7).toLong()
      if (isNextWeek) nextDiff + 7L else nextDiff
    }
    return (nowLocalDay + diff) to m.range
  }

  private fun parseThaiMonthDate(
    text: String, curYear: Int, curMonth: Int, curDay: Int, isTimeAhead: Boolean,
  ): Pair<Long, IntRange>? {
    val m = Regex("""(?:ในวันที่|ในวัน|วันที่|วัน)?\s*(\d{1,2})\s*(ม\.ค\.|ก\.พ\.|มี\.ค\.|เม\.ย\.|พ\.ค\.|มิ\.ย\.|ก\.ค\.|ส\.ค\.|ก\.ย\.|ต\.ค\.|พ\.ย\.|ธ\.ค\.|มกราคม|กุมภาพันธ์|มีนาคม|เมษายน|พฤษภาคม|มิถุนายน|กรกฎาคม|สิงหาคม|กันยายน|ตุลาคม|พฤศจิกายน|ธันวาคม)(?:\s*(?:พ\.ศ\.|ค\.ศ\.)?\s*(\d{4}))?""").find(text) ?: return null
    val d = m.groupValues[1].toInt()
    val mo = MONTHS[m.groupValues[2]] ?: return null
    val yrStr = m.groupValues[3]
    val yr = if (yrStr.isNotEmpty()) {
      val y = yrStr.toInt()
      if (y > 2400) y - 543 else y
    } else {
      val passed = mo < curMonth || (mo == curMonth && (d < curDay || (d == curDay && !isTimeAhead)))
      if (passed) curYear + 1 else curYear
    }
    return dateToEpochDay(yr, mo, d) to m.range
  }

  private fun parseSlashDate(
    text: String, curYear: Int, curMonth: Int, curDay: Int, isTimeAhead: Boolean,
  ): Pair<Long, IntRange>? {
    val m = Regex("""(?:ในวันที่|ในวัน|วันที่|วัน)?\s*(\d{1,2})/(\d{1,2})(?:/(\d{4}))?""").find(text) ?: return null
    val d = m.groupValues[1].toInt()
    val mo = m.groupValues[2].toInt()
    if (mo !in 1..12 || d !in 1..31) return null
    val yrStr = m.groupValues[3]
    val yr = if (yrStr.isNotEmpty()) {
      val y = yrStr.toInt()
      if (y > 2400) y - 543 else y
    } else {
      val passed = mo < curMonth || (mo == curMonth && (d < curDay || (d == curDay && !isTimeAhead)))
      if (passed) curYear + 1 else curYear
    }
    return dateToEpochDay(yr, mo, d) to m.range
  }

  private fun parseBareDayDate(
    text: String, curYear: Int, curMonth: Int, curDay: Int, isTimeAhead: Boolean,
  ): Pair<Long, IntRange>? {
    val m = Regex("""(?:ในวันที่|วันที่)\s*(\d{1,2})\b""").find(text) ?: return null
    val d = m.groupValues[1].toInt()
    if (d !in 1..31) return null
    val passed = d < curDay || (d == curDay && !isTimeAhead)
    val (yr, mo) = if (passed) {
      if (curMonth == 12) (curYear + 1) to 1 else curYear to (curMonth + 1)
    } else {
      curYear to curMonth
    }
    return dateToEpochDay(yr, mo, d) to m.range
  }

  private fun dateToEpochDay(y: Int, m: Int, d: Int): Long {
    val iso = "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T00:00:00.000Z"
    return MemoryFormat.parseUtc(iso)!!.floorDiv(DAY_MS)
  }

  private fun cleanTitle(text: String, timeRange: IntRange, dateRange: IntRange?): String {
    val chars = text.toCharArray()
    for (i in timeRange) chars[i] = ' '
    if (dateRange != null) {
      for (i in dateRange) chars[i] = ' '
    }
    var s = chars.concatToString()
    // ponytail: remove leading appointment cues, particles, punctuation; fallback to "นัด" if blank
    s = s.replaceFirst(Regex("""^\s*(?:มีนัด|นัด)\s*"""), "")
    s = s.replace(Regex("""(?<=^|[\s,;!?])(?:ครับผม|นะครับ|นะคะ|ครับ|ค่ะ|คะ|นะ|จ้า|คับ)(?=[\s,;!?]|$)"""), " ")
    s = s.replace(Regex("""(?:ครับผม|นะครับ|นะคะ|ครับ|ค่ะ|คะ|นะ|จ้า|คับ)+$"""), "")
    s = s.replace(Regex("""[.,\-—–_!?:;\"'()\[\]{}]+"""), " ")
    s = s.replace(Regex("""\s+"""), " ").trim()
    return if (s.isBlank()) "นัด" else s
  }
}
