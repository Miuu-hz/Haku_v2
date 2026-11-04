package com.bbpillow.haku.surface.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMarkdownTest {
  @Test
  fun testBoldInMiddleGivesThreeSpans() {
    val blocks = ChatMarkdown.parse("hello **bold** world")
    assertEquals(1, blocks.size)
    val p = blocks[0] as MdBlock.Paragraph
    assertEquals(3, p.spans.size)
    assertEquals(MdSpan("hello "), p.spans[0])
    assertEquals(MdSpan("bold", bold = true), p.spans[1])
    assertEquals(MdSpan(" world"), p.spans[2])
  }

  @Test
  fun testHeadingBulletsAndNumbered() {
    val text = """
      # หัวข้อ
      * bullet star
      - bullet dash
      2. item two
        * nested bullet
    """.trimIndent()
    val blocks = ChatMarkdown.parse(text)
    assertEquals(5, blocks.size)

    val heading = blocks[0] as MdBlock.Heading
    assertEquals(listOf(MdSpan("หัวข้อ")), heading.spans)

    val bStar = blocks[1] as MdBlock.ListItem
    assertEquals("•", bStar.marker)
    assertEquals(0, bStar.depth)
    assertEquals(listOf(MdSpan("bullet star")), bStar.spans)

    val bDash = blocks[2] as MdBlock.ListItem
    assertEquals("•", bDash.marker)
    assertEquals(0, bDash.depth)
    assertEquals(listOf(MdSpan("bullet dash")), bDash.spans)

    val numbered = blocks[3] as MdBlock.ListItem
    assertEquals("2.", numbered.marker)
    assertEquals(0, numbered.depth)
    assertEquals(listOf(MdSpan("item two")), numbered.spans)

    val nested = blocks[4] as MdBlock.ListItem
    assertEquals("•", nested.marker)
    assertEquals(1, nested.depth)
    assertEquals(listOf(MdSpan("nested bullet")), nested.spans)
  }

  @Test
  fun testFenceWithLanguageAndUnclosed() {
    val withLang = """
      ```kotlin
      val x = 42
      ```
    """.trimIndent()
    val blocks1 = ChatMarkdown.parse(withLang)
    assertEquals(1, blocks1.size)
    assertEquals(MdBlock.Code("val x = 42"), blocks1[0])

    val unclosed = """
      ```python
      print("stream")
    """.trimIndent()
    val blocks2 = ChatMarkdown.parse(unclosed)
    assertEquals(1, blocks2.size)
    assertEquals(MdBlock.Code("print(\"stream\")"), blocks2[0])
  }

  @Test
  fun testUnclosedMarkAndMathExpressionStayPlain() {
    val unclosed = ChatMarkdown.parse("**กุ้ง")
    assertEquals(1, unclosed.size)
    val p1 = unclosed[0] as MdBlock.Paragraph
    assertEquals(listOf(MdSpan("**กุ้ง")), p1.spans)

    val math = ChatMarkdown.parse("2 * 3 * 4")
    assertEquals(1, math.size)
    val p2 = math[0] as MdBlock.Paragraph
    assertEquals(listOf(MdSpan("2 * 3 * 4")), p2.spans)
  }

  @Test
  fun testBlankInputGivesEmptyList() {
    assertEquals(emptyList(), ChatMarkdown.parse(""))
    assertEquals(emptyList(), ChatMarkdown.parse("   "))
    assertEquals(emptyList(), ChatMarkdown.parse("\n\n  \t\n"))
  }

  @Test
  fun testThaiSample() {
    val sample = "**ส่วนผสม:**\n\n*   **กุ้ง:** 500 กรัม\n*   น้ำ 2 ลิตร"
    val blocks = ChatMarkdown.parse(sample)
    assertEquals(3, blocks.size)

    val p = blocks[0] as MdBlock.Paragraph
    assertEquals(listOf(MdSpan("ส่วนผสม:", bold = true)), p.spans)

    val item1 = blocks[1] as MdBlock.ListItem
    assertEquals("•", item1.marker)
    assertEquals(0, item1.depth)
    assertEquals(listOf(MdSpan("กุ้ง:", bold = true), MdSpan(" 500 กรัม")), item1.spans)

    val item2 = blocks[2] as MdBlock.ListItem
    assertEquals("•", item2.marker)
    assertEquals(0, item2.depth)
    assertEquals(listOf(MdSpan("น้ำ 2 ลิตร")), item2.spans)
  }

  @Test
  fun testMixedSampleKeepsAllWords() {
    val sample = """
      # แนะนำเมนู
      วันนี้มีอาหารพิเศษ **ต้มยำกุ้ง** รสจัดจ้านและ *สลัดผัก* สดใหม่
      ใช้โค้ด `HAKU20` เพื่อรับส่วนลด
      * สั่งได้ถึง `20:00`
      2. จัดส่งฟรีเมื่อสั่งครบ 500 บาท
    """.trimIndent()
    val blocks = ChatMarkdown.parse(sample)
    val allText = blocks.joinToString(" ") { block ->
      when (block) {
        is MdBlock.Heading -> block.spans.joinToString("") { it.text }
        is MdBlock.Paragraph -> block.spans.joinToString("") { it.text }
        is MdBlock.ListItem -> block.spans.joinToString("") { it.text }
        is MdBlock.Code -> block.text
      }
    }
    val words = listOf("แนะนำเมนู", "วันนี้มีอาหารพิเศษ", "ต้มยำกุ้ง", "รสจัดจ้านและ", "สลัดผัก", "สดใหม่", "HAKU20", "20:00", "จัดส่งฟรีเมื่อสั่งครบ 500 บาท")
    for (word in words) {
      assertTrue(allText.contains(word), "Missing word '$word' in '$allText'")
    }
  }

  @Test
  fun testAdjacentPlainLinesJoinIntoOneParagraph() {
    val sample = "บรรทัดที่ 1\nบรรทัดที่ 2"
    val blocks = ChatMarkdown.parse(sample)
    assertEquals(1, blocks.size)
    val p = blocks[0] as MdBlock.Paragraph
    assertEquals("บรรทัดที่ 1\nบรรทัดที่ 2", p.spans.joinToString("") { it.text })
  }
}
