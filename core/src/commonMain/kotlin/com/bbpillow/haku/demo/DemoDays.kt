package com.bbpillow.haku.demo

import com.bbpillow.haku.demo.DemoPlace.BOOKSTORE
import com.bbpillow.haku.demo.DemoPlace.CAFE
import com.bbpillow.haku.demo.DemoPlace.DENTIST
import com.bbpillow.haku.demo.DemoPlace.GYM
import com.bbpillow.haku.demo.DemoPlace.HOME
import com.bbpillow.haku.demo.DemoPlace.LUNCH
import com.bbpillow.haku.demo.DemoPlace.MALL
import com.bbpillow.haku.demo.DemoPlace.MOM
import com.bbpillow.haku.demo.DemoPlace.OFFICE
import com.bbpillow.haku.demo.DemoPlace.PARK
import com.bbpillow.haku.demo.DemoSeed.Companion.hhmm

/** Times are local minutes of the day; a stay may end at 1440 (it continues past midnight). */
data class DemoStay(val place: DemoPlace, val start: Int, val end: Int)
data class DemoAppointment(val title: String, val start: Int, val end: Int)
data class DemoNote(val minute: Int, val text: String)

/** One day of the persona. [summary] is the hand-written evening paragraph; it may only restate this day's diary. */
data class DemoDayPlan(
  val stays: List<DemoStay>,
  val appts: List<DemoAppointment> = emptyList(),
  val notes: List<DemoNote> = emptyList(),
  val runs: List<Pair<Int, Int>> = emptyList(),
  val summary: String,
)

/**
 * The persona's week (haku-mockup.html: Thursday = the mockup day, 08:12 out, coffee, 12:30 chicken rice, sales meeting,
 * 18:02 out, 18:10 gym). [occurrence] 0 = first week of the two, 1 = this week.
 */
internal object DemoDays {
  private const val END = 1440
  private val SLEEP_NOTE = DemoNote(1350, "ตั้งใจนอนก่อนห้าทุ่ม")

  fun plan(weekday: Int, occurrence: Int, jitter: Int): DemoDayPlan = when (weekday) {
    0 -> if (occurrence == 0) dentist(jitter) else gym(jitter, monday = true)
    1 -> straightHome(jitter)
    2 -> if (occurrence == 0) bookstore(jitter) else gym(jitter, monday = false)
    3 -> gym(jitter, monday = false, thursday = true)
    4 -> friday(jitter)
    5 -> saturday(jitter)
    else -> sunday(jitter)
  }

  /** Home, coffee, office (with a chicken-rice lunch when [lunch]) until [officeEnd]. Returns stays and the leave time. */
  private fun morning(j: Int, lunch: Boolean, officeEnd: Int): Pair<List<DemoStay>, String> {
    val leave = 492 + j // 08:12 on the mockup day
    val office = if (lunch) {
      listOf(DemoStay(OFFICE, 525 + j, 744), DemoStay(LUNCH, 750, 790), DemoStay(OFFICE, 795, officeEnd))
    } else {
      listOf(DemoStay(OFFICE, 525 + j, officeEnd))
    }
    return (listOf(DemoStay(HOME, 0, leave), DemoStay(CAFE, 506 + j, 522 + j)) + office) to hhmm(leave)
  }

  private val mondayMeeting = DemoAppointment("ประชุมเช้าวันจันทร์", 570, 600)

  private fun gym(j: Int, monday: Boolean, thursday: Boolean = false): DemoDayPlan {
    val (day, leave) = morning(j, lunch = true, officeEnd = 1082)
    val appts = listOfNotNull(mondayMeeting.takeIf { monday }, DemoAppointment("ประชุมทีมขาย", 810, 870).takeIf { thursday })
    val middle = when {
      thursday -> " เที่ยงกินร้านข้าวมันไก่ บ่ายมีนัด “ประชุมทีมขาย” ทีมตกลงส่งใบเสนอราคาใหม่ภายในวันศุกร์"
      monday -> " เริ่มสัปดาห์ด้วยนัด “ประชุมเช้าวันจันทร์” เที่ยงกินร้านข้าวมันไก่"
      else -> " เที่ยงกินร้านข้าวมันไก่เหมือนเคย"
    }
    return DemoDayPlan(
      stays = day + listOf(DemoStay(GYM, 1090, 1150), DemoStay(HOME, 1185, END)),
      appts = appts,
      notes = listOfNotNull(DemoNote(880, "ทีมขายตกลงส่งใบเสนอราคาใหม่ภายในวันศุกร์").takeIf { thursday }, SLEEP_NOTE.takeIf { monday }),
      summary = "วันนี้ออกจากบ้านตอน $leave แวะร้านกาแฟหน้าออฟฟิศก่อนเข้างาน$middle แล้วปิดวันที่ฟิตเนสก่อนกลับบ้านค่ะ",
    )
  }

  private fun straightHome(j: Int): DemoDayPlan {
    val (day, leave) = morning(j, lunch = false, officeEnd = 1080 + j)
    return DemoDayPlan(
      stays = day + DemoStay(HOME, 1120 + j, END),
      appts = listOf(DemoAppointment("โทรคุยลูกค้า", 600, 630)),
      notes = listOf(SLEEP_NOTE),
      summary = "วันนี้ออกจากบ้านตอน $leave แวะร้านกาแฟหน้าออฟฟิศเหมือนทุกเช้า สายมีนัด “โทรคุยลูกค้า” เลิกงานแล้วกลับบ้านเลย เป็นวันเรียบ ๆ ที่ได้พักเร็วค่ะ",
    )
  }

  private fun dentist(j: Int): DemoDayPlan {
    val (day, leave) = morning(j, lunch = true, officeEnd = 1020)
    return DemoDayPlan(
      stays = day + listOf(DemoStay(DENTIST, 1065, 1110), DemoStay(HOME, 1130, END)),
      appts = listOf(mondayMeeting, DemoAppointment("หมอฟัน", 1065, 1110)),
      notes = listOf(DemoNote(1115, "หมอนัดขูดหินปูนอีกทีปีหน้า"), SLEEP_NOTE),
      summary = "วันนี้ออกจากบ้านตอน $leave เริ่มสัปดาห์ด้วยนัด “ประชุมเช้าวันจันทร์” เย็นออกจากที่ทำงานเร็วกว่าปกติเพื่อไปนัด “หมอฟัน” " +
        "ที่คลินิกทันตกรรม หมอนัดขูดหินปูนอีกทีปีหน้าค่ะ",
    )
  }

  private fun bookstore(j: Int): DemoDayPlan {
    val (day, leave) = morning(j, lunch = true, officeEnd = 1080)
    return DemoDayPlan(
      stays = day + listOf(DemoStay(BOOKSTORE, 1110, 1160), DemoStay(HOME, 1190, END)),
      notes = listOf(DemoNote(1165, "ได้หนังสือเที่ยวเชียงใหม่มาหนึ่งเล่ม"), SLEEP_NOTE),
      summary = "วันนี้ออกจากบ้านตอน $leave แวะร้านกาแฟหน้าออฟฟิศก่อนเข้างาน เที่ยงกินร้านข้าวมันไก่ " +
        "หลังเลิกงานแวะร้านหนังสือ สยาม ได้หนังสือเที่ยวเชียงใหม่มาหนึ่งเล่มค่ะ",
    )
  }

  private fun friday(j: Int): DemoDayPlan {
    val (day, leave) = morning(j, lunch = false, officeEnd = 1070)
    return DemoDayPlan(
      stays = day + listOf(DemoStay(MALL, 1105, 1230), DemoStay(HOME, 1265, END)),
      appts = listOf(DemoAppointment("กินข้าวกับเพื่อน", 1140, 1230)),
      notes = listOf(DemoNote(1235, "เพื่อนชวนไปเชียงใหม่เดือนธันวา")),
      summary = "วันศุกร์ออกจากบ้านตอน $leave แวะร้านกาแฟหน้าออฟฟิศเหมือนเคย เย็นไปเซ็นทรัลเวิลด์ตามนัด “กินข้าวกับเพื่อน” " +
        "เพื่อนชวนไปเชียงใหม่เดือนธันวาด้วยค่ะ",
    )
  }

  private fun saturday(j: Int) = DemoDayPlan(
    stays = listOf(
      DemoStay(HOME, 0, 400 + j), DemoStay(PARK, 420 + j, 495 + j), DemoStay(HOME, 520 + j, 790),
      DemoStay(MALL, 825, 1020), DemoStay(HOME, 1050, END),
    ),
    runs = listOf(425 + j to 480 + j),
    notes = listOf(DemoNote(500 + j, "วิ่งรอบสวนได้สามรอบ")),
    summary = "เช้าวันหยุดออกไปวิ่งที่สวนลุมพินี วิ่งรอบสวนได้สามรอบแล้วกลับมาพักที่บ้าน บ่ายไปเดินเล่นที่เซ็นทรัลเวิลด์ก่อนกลับบ้านตอนเย็นค่ะ",
  )

  private fun sunday(j: Int) = DemoDayPlan(
    stays = listOf(DemoStay(HOME, 0, 630 + j), DemoStay(MOM, 675 + j, 990), DemoStay(HOME, 1035, END)),
    notes = listOf(DemoNote(980, "แม่ทำแกงเขียวหวานให้ห่อกลับบ้าน")),
    summary = "วันอาทิตย์ใช้เวลาเกือบทั้งวันที่บ้านแม่ นนทบุรี แม่ทำแกงเขียวหวานให้ห่อกลับบ้าน ตกเย็นถึงบ้านแล้วพักผ่อนค่ะ",
  )
}
