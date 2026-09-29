package com.bbpillow.haku.platform

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test suite #2 (doc 03 section 10): word boundary accuracy of the OS breaker on Thai.
 * Gold = the usual dictionary segmentation, words separated by "|". Small starter set (doc asks for 500).
 */
@RunWith(AndroidJUnit4::class)
class IcuWordSegmenterTest {
  private val gold = listOf(
    "ฉัน|กิน|ข้าว|ที่|บ้าน",
    "วัน|นี้|อากาศ|ดี|มาก",
    "พรุ่งนี้|ไป|ทำงาน|ตอน|เช้า",
    "เมื่อวาน|ฉัน|ไป|ตลาด|กับ|แม่",
    "ผม|อยาก|ดื่ม|กาแฟ|ร้อน",
    "เขา|ชอบ|อ่าน|หนังสือ",
    "เรา|นัด|เจอ|กัน|ตอน|บ่าย|สอง",
    "ฝน|ตก|หนัก|มาก|เมื่อ|คืน|นี้",
    "แม่|ทำ|อาหาร|อร่อย|มาก",
    "ลูก|ไป|โรงเรียน|ทุก|วัน",
    "ฉัน|ปวด|หัว|และ|มี|ไข้",
    "คุณ|หมอ|ให้|ยา|มา|กิน",
    "เขา|เดินทาง|ไป|เชียงใหม่|กับ|เพื่อน",
    "เรา|ต้อง|ส่ง|งาน|ภายใน|วัน|ศุกร์",
    "ร้าน|กาแฟ|ใกล้|บ้าน|เปิด|แล้ว",
    "ผม|ลืม|กุญแจ|ไว้|ที่|ออฟฟิศ",
    "เธอ|ดีใจ|ที่|ได้|เจอ|เพื่อน|เก่า",
    "พี่|สาว|ซื้อ|เสื้อ|ตัว|ใหม่",
    "พวก|เรา|ไป|เที่ยว|ทะเล|กัน",
    "เขา|เหนื่อย|มาก|หลัง|จาก|ทำงาน",
    "ฉัน|จะ|โทร|หา|คุณ|พรุ่งนี้",
    "วัน|เกิด|ของ|แฟน|อยู่|เดือน|หน้า",
    "รถ|ติด|มาก|เลย|วัน|นี้",
    "เขา|จ่าย|เงิน|ค่า|อาหาร|แล้ว",
    "เรา|นั่ง|รถไฟฟ้า|ไป|ทำงาน",
    "ครู|สอน|นักเรียน|อ่าน|หนังสือ",
    "ฉัน|รัก|ประเทศ|ไทย",
    "เขา|พูด|ภาษา|อังกฤษ|ได้|ดี",
    "ผม|นอน|ไม่|หลับ|เมื่อ|คืน",
    "เรา|ประชุม|กัน|ที่|บริษัท",
  )

  private fun boundaries(words: List<String>): Set<Int> =
    words.runningFold(0) { acc, w -> acc + w.length }.drop(1).dropLast(1).toSet()

  @Test
  fun thaiBoundaryAccuracyIsAtLeastNinetyPercent() {
    val segmenter = IcuWordSegmenter()
    var goldTotal = 0
    var hit = 0
    var predTotal = 0
    for (line in gold) {
      val goldWords = line.split("|")
      val text = goldWords.joinToString("")
      val predWords = segmenter.segment(text, "th").map { it.text }
      val g = boundaries(goldWords)
      val p = boundaries(predWords)
      goldTotal += g.size
      predTotal += p.size
      hit += g.intersect(p).size
      if (g != p) Log.i("SegGold", "MISS gold=${goldWords.joinToString("|")} icu=${predWords.joinToString("|")}")
    }
    val recall = 100.0 * hit / goldTotal
    val precision = 100.0 * hit / predTotal
    Log.i("SegGold", "sentences=${gold.size} recall=%.1f%% precision=%.1f%%".format(recall, precision))
    assertTrue("boundary recall $recall% < 90%", recall >= 90.0)
  }

  @Test
  fun skipsSpacesAndPunctuationAndKeepsOffsets() {
    val text = "ไป ตลาด, ok!"
    val tokens = IcuWordSegmenter().segment(text, "th")
    assertTrue(tokens.all { text.substring(it.start, it.end) == it.text && it.text.isNotBlank() })
    assertTrue(tokens.map { it.text }.containsAll(listOf("ไป", "ok")))
  }
}
