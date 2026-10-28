package com.bbpillow.haku.capture.stats

import com.bbpillow.haku.capture.PlaceCategory
import com.bbpillow.haku.capture.diary.DiaryTemplates

/**
 * DEVELOPER REVIEW: every Thai sentence statistics can say lives in this file and nowhere else (D14).
 * Have a Thai speaker read each output before release. Every claim carries its numbers; none is written without them.
 *
 * Shapes ([who] = สัปดาห์นี้ / เดือนนี้ / ปีนี้ / "1 ก.ย. - 30 ก.ย."):
 *   สัปดาห์นี้ มีข้อมูล 6 จาก 7 วัน
 *   อยู่บ้านราว 98 ชั่วโมง ที่ทำงานราว 40 ชั่วโมง อยู่ที่อื่นราว 10 ชั่วโมง
 *   อยู่ใน 4 สถานที่ (ที่ใหม่ 1 แห่ง) ไป “คาเฟ่” บ่อยที่สุด 6 ครั้ง
 *   เดินทางรวมราว 1 ชั่วโมง 20 นาที / มีนัด 2 รายการ
 *   วันจันทร์ออกไปข้างนอกบ่อยที่สุด เฉลี่ยวันละราว 2 ชั่วโมง (จาก 4 วันจันทร์ วันอื่นไม่เกินราว 1 ชั่วโมง)
 *   เดือนนี้อยู่นอกบ้านเฉลี่ยวันละราว 9 ชั่วโมง มากกว่าเดือนก่อน (วันละราว 7 ชั่วโมง)
 *   เดือนนี้ไป “ร้านข้าว” 8 ครั้ง เดือนก่อน 3 ครั้ง
 *   (stats screen, no comparison claim) ข้อมูลยังไม่พอจะเทียบกับเดือนก่อน / ยังไม่ต่างจากเดือนก่อนชัดเจน
 */
object StatsTemplates {
  const val NO_DATA = "ยังไม่มีข้อมูลพอจะสรุป"
  private val weekdayNames = listOf("จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์")

  fun thisPeriod(kind: PeriodKind) = when (kind) { PeriodKind.WEEK -> "สัปดาห์นี้"; PeriodKind.MONTH -> "เดือนนี้"; PeriodKind.YEAR -> "ปีนี้" }
  fun lastPeriod(kind: PeriodKind) = when (kind) { PeriodKind.WEEK -> "สัปดาห์ก่อน"; PeriodKind.MONTH -> "เดือนก่อน"; PeriodKind.YEAR -> "ปีก่อน" }

  fun coverage(who: String, s: PeriodStats) = "$who มีข้อมูล ${s.coveredDays} จาก ${s.period.dayCount} วัน"

  fun timeSplit(s: PeriodStats): String? {
    val parts = listOfNotNull(
      s.homeMs?.let { "อยู่บ้านราว ${DiaryTemplates.duration(it)}" },
      s.workMs?.let { "ที่ทำงานราว ${DiaryTemplates.duration(it)}" },
      s.otherMs?.let { "อยู่ที่อื่นราว ${DiaryTemplates.duration(it)}" },
    )
    return parts.joinToString(" ").ifEmpty { null }
  }

  fun places(s: PeriodStats): String? {
    if (s.distinctPlaces == 0) return null
    val fresh = s.newPlaces?.takeIf { it > 0 }?.let { " (ที่ใหม่ $it แห่ง)" }.orEmpty()
    val top = s.placeUses.firstOrNull { it.category == PlaceCategory.OTHER && !it.ref.label.isNullOrBlank() }
    val most = top?.let { " ไป “${it.ref.label}” บ่อยที่สุด ${it.visits} ครั้ง" }.orEmpty()
    return "อยู่ใน ${s.distinctPlaces} สถานที่$fresh$most"
  }

  fun travelAndAppointments(s: PeriodStats): String? {
    val parts = listOfNotNull(
      s.travelMs.takeIf { it > 0 }?.let { "เดินทางรวมราว ${DiaryTemplates.duration(it)}" },
      s.appointments.takeIf { it > 0 }?.let { "มีนัด $it รายการ" },
    )
    return parts.joinToString(" ").ifEmpty { null }
  }

  /** The comparison section when no claim passed: one side has under half its days recorded, or nothing changed enough. */
  fun noComparison(kind: PeriodKind, current: PeriodStats, previous: PeriodStats) =
    if (current.isReliable && previous.isReliable) "ยังไม่ต่างจาก${lastPeriod(kind)}ชัดเจน" else "ข้อมูลยังไม่พอจะเทียบกับ${lastPeriod(kind)}"

  fun insight(i: StatsInsight, kind: PeriodKind): String = when (i) {
    is StatsInsight.BusiestWeekday -> {
      val name = weekdayNames[i.weekday]
      val others = if (i.runnerUpAvgMs > 0) " วันอื่นไม่เกินราว ${DiaryTemplates.duration(i.runnerUpAvgMs)}" else ""
      "วัน${name}ออกไปข้างนอกบ่อยที่สุด เฉลี่ยวันละราว ${DiaryTemplates.duration(i.avgOtherMs)} (จาก ${i.occurrences} วัน$name$others)"
    }
    is StatsInsight.AwayFromHomeChanged -> {
      val more = if (i.currentAvgMs > i.previousAvgMs) "มากกว่า" else "น้อยกว่า"
      "${thisPeriod(kind)}อยู่นอกบ้านเฉลี่ยวันละราว ${DiaryTemplates.duration(i.currentAvgMs)} $more${lastPeriod(kind)} (วันละราว ${DiaryTemplates.duration(i.previousAvgMs)})"
    }
    is StatsInsight.PlaceVisitsChanged ->
      "${thisPeriod(kind)}ไป “${i.place.ref.label}” ${i.current} ครั้ง ${lastPeriod(kind)} ${i.previous} ครั้ง"
  }
}
