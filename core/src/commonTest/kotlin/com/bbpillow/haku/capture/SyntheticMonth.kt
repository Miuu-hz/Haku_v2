package com.bbpillow.haku.capture

/**
 * Deterministic Aug 1 to Sep 30 2026 (Bangkok) of recognized stays, built on [SyntheticWeek.at] (day 0 = Mon 2026-09-07).
 * August: office 09:00-13:00 weekdays, no lunch out. September: office 09:00-18:00, 40 min lunch at FOOD on weekdays.
 * Both months: CAFE 3 h every Saturday; home otherwise. Weekdays leave home 08:15 (45 min trip to the office).
 */
object SyntheticMonth {
  const val FIRST_DAY = -37 // Sat 2026-08-01
  const val LAST_DAY = 23 // Wed 2026-09-30
  const val SEP_FIRST_DAY = -6 // Tue 2026-09-01

  val home = PlaceRecord("home", 13.7563, 100.5018, "บ้าน", PlaceCategory.HOME, true)
  val work = PlaceRecord("work", 13.8013, 100.5018, "ที่ทำงาน", PlaceCategory.WORK, true)
  val cafe = PlaceRecord("cafe", 13.7563, 100.5108, "คาเฟ่", PlaceCategory.OTHER, true)
  val food = PlaceRecord("food", 13.7600, 100.5100, "ร้านข้าว", PlaceCategory.OTHER, true)
  val places = listOf(home, work, cafe, food)

  fun visits(fromDay: Int = FIRST_DAY, toDay: Int = LAST_DAY): List<PlaceVisitRecord> = buildList {
    for (d in fromDay..toDay) {
      val sep = d >= SEP_FIRST_DAY
      val at = SyntheticWeek::at
      when (d.mod(7)) { // day 0 is Monday
        in 0..4 -> {
          add(PlaceVisitRecord("home", at(d, 0, 0), at(d, 8, 15)))
          add(PlaceVisitRecord("work", at(d, 9, 0), at(d, if (sep) 12 else 13, 0)))
          if (sep) {
            add(PlaceVisitRecord("food", at(d, 12, 10), at(d, 12, 50)))
            add(PlaceVisitRecord("work", at(d, 13, 0), at(d, 18, 0)))
          }
          add(PlaceVisitRecord("home", at(d, 19, 0), at(d + 1, 0, 0)))
        }
        5 -> {
          add(PlaceVisitRecord("home", at(d, 0, 0), at(d, 10, 0)))
          add(PlaceVisitRecord("cafe", at(d, 10, 30), at(d, 13, 30)))
          add(PlaceVisitRecord("home", at(d, 14, 0), at(d + 1, 0, 0)))
        }
        else -> add(PlaceVisitRecord("home", at(d, 0, 0), at(d + 1, 0, 0)))
      }
    }
  }
}
