package com.bbpillow.haku.memory.index

import kotlin.random.Random

/**
 * Deterministic fake Thai diary (P0.4 spike, ~2-3 years of use = 10k records). Thai is written unspaced like
 * real text; English brands and digits are mixed in. Not real data. Ships in commonMain so the device benchmark can reuse it.
 */
object SyntheticDiary {
  private val times = listOf("เช้านี้", "บ่ายวันนี้", "เมื่อวาน", "เย็นนี้", "ตอนเที่ยง", "คืนวันศุกร์", "วันเสาร์", "เมื่อวานเย็น")
  private val people = listOf("กับแม่", "กับเพื่อน", "กับแฟน", "กับทีม", "กับลูกค้า", "กับพี่ชาย", "คนเดียว")
  private val activities = listOf("ไปวิ่ง", "ไปกินข้าว", "ประชุม", "ดูหนัง", "ซื้อของ", "ทำงาน", "คุยงาน", "ไปเที่ยว", "อ่านหนังสือ", "ออกกำลังกาย", "นัดหมอ", "ซ่อมรถ")
  private val places = listOf("ที่สวนลุมพินี", "ที่ห้างสยามพารากอน", "ที่เซ็นทรัลเวิลด์", "ที่ตลาดนัดจตุจักร", "ที่ออฟฟิศ", "ที่บ้าน", "ที่โรงพยาบาล", "ที่เชียงใหม่", "ที่สนามบินดอนเมือง", "ที่ร้านกาแฟ", "ที่ทะเลหัวหิน")
  private val feelings = listOf("รู้สึกเหนื่อยมาก", "สนุกดี", "อร่อยมาก", "รถติดหนัก", "ฝนตกทั้งวัน", "ดีใจสุดๆ", "เครียดนิดหน่อย")
  private val english = listOf("Starbucks", "meeting", "deadline", "project", "standup", "Grab", "Netflix", "coffee", "sprint review", "LINE")

  /** Every Thai word used above; a dictionary segmenter built from this segments the generated text exactly. */
  val thaiVocabulary: Set<String> = setOf(
    "เช้า", "นี้", "บ่าย", "วันนี้", "เมื่อวาน", "เย็น", "ตอน", "เที่ยง", "คืน", "วัน", "ศุกร์", "เสาร์",
    "กับ", "แม่", "เพื่อน", "แฟน", "ทีม", "ลูกค้า", "พี่ชาย", "คน", "เดียว",
    "ไป", "วิ่ง", "กิน", "ข้าว", "ประชุม", "ดู", "หนัง", "ซื้อ", "ของ", "ทำงาน", "คุย", "งาน", "เที่ยว", "อ่าน", "หนังสือ", "ออกกำลังกาย", "นัด", "หมอ", "ซ่อม", "รถ",
    "ที่", "สวน", "ลุมพินี", "ห้าง", "สยามพารากอน", "เซ็นทรัลเวิลด์", "ตลาด", "จตุจักร", "ออฟฟิศ", "บ้าน", "โรงพยาบาล", "เชียงใหม่", "สนามบิน", "ดอนเมือง", "ร้าน", "กาแฟ", "ทะเล", "หัวหิน",
    "รู้สึก", "เหนื่อย", "มาก", "สนุก", "ดี", "อร่อย", "ติด", "หนัก", "ฝน", "ตก", "ทั้ง", "ดีใจ", "สุดๆ", "เครียด", "นิดหน่อย",
  )

  fun records(count: Int = 10_000, seed: Long = 42): List<IndexRecordInput> {
    val r = Random(seed)
    return List(count) { i ->
      val parts = mutableListOf(times.random(r) + people.random(r) + activities.random(r) + places.random(r))
      if (r.nextInt(100) < 45) parts += english.random(r)
      if (r.nextInt(100) < 30) parts += "${r.nextInt(8, 21)}:${r.nextInt(0, 6)}0"
      if (r.nextInt(100) < 60) parts += feelings.random(r)
      IndexRecordInput(id = "r$i", text = parts.joinToString(" "))
    }
  }

  /** Query words for the benchmark: mixed Thai and English, seeded. */
  fun queries(count: Int = 50, seed: Long = 7): List<String> {
    val r = Random(seed)
    val pool = (times + activities + places.map { it.removePrefix("ที่") } + english).flatMap { it.split(" ") }
    return List(count) { pool.random(r) }
  }
}
