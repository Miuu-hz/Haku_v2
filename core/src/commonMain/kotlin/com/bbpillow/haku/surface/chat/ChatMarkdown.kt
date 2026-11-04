package com.bbpillow.haku.surface.chat

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

sealed interface MdBlock {
  data class Heading(val spans: List<MdSpan>) : MdBlock
  data class Paragraph(val spans: List<MdSpan>) : MdBlock
  /** [marker] is "•" for a bullet or "3." for a numbered item; [depth] 0 for top level, 1 for a nested one. */
  data class ListItem(val marker: String, val depth: Int, val spans: List<MdSpan>) : MdBlock
  data class Code(val text: String) : MdBlock
}

object ChatMarkdown {
  private val HEADING = Regex("""^#{1,3} +(.*)$""")
  private val BULLET = Regex("""^( *)([-*+]) +(.*)$""")
  private val NUMBERED = Regex("""^( *)(\d+\.) +(.*)$""")

  fun parse(text: String): List<MdBlock> {
    if (text.isBlank()) return emptyList()
    val blocks = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    var inFence = false
    val fence = mutableListOf<String>()

    fun flushPara() {
      if (para.isNotEmpty()) {
        blocks += MdBlock.Paragraph(parseSpans(para.joinToString("\n")))
        para.clear()
      }
    }

    for (line in text.lines()) {
      if (inFence) {
        if (line.trimStart().startsWith("```")) {
          blocks += MdBlock.Code(fence.joinToString("\n"))
          fence.clear()
          inFence = false
        } else {
          fence += line
        }
      } else if (line.trimStart().startsWith("```")) {
        flushPara()
        inFence = true
        fence.clear()
      } else if (line.isBlank()) {
        flushPara()
      } else {
        val h = HEADING.matchEntire(line)
        val b = BULLET.matchEntire(line)
        val n = NUMBERED.matchEntire(line)
        when {
          h != null -> {
            flushPara()
            blocks += MdBlock.Heading(parseSpans(h.groupValues[1]))
          }
          b != null -> {
            flushPara()
            val depth = if (b.groupValues[1].length >= 2) 1 else 0
            blocks += MdBlock.ListItem("•", depth, parseSpans(b.groupValues[3]))
          }
          n != null -> {
            flushPara()
            val depth = if (n.groupValues[1].length >= 2) 1 else 0
            blocks += MdBlock.ListItem(n.groupValues[2], depth, parseSpans(n.groupValues[3]))
          }
          else -> para += line
        }
      }
    }
    if (inFence) blocks += MdBlock.Code(fence.joinToString("\n")) else flushPara()
    return blocks
  }

  private fun parseSpans(text: String): List<MdSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<MdSpan>()
    val plain = StringBuilder()
    fun flushPlain() {
      if (plain.isNotEmpty()) {
        spans += MdSpan(plain.toString())
        plain.clear()
      }
    }

    var i = 0
    while (i < text.length) {
      if (text[i] == '`') {
        val close = text.indexOf('`', i + 1)
        if (close > i + 1) {
          flushPlain()
          spans += MdSpan(text.substring(i + 1, close), code = true)
          i = close + 1
          continue
        }
      } else if (text.startsWith("**", i)) {
        val close = text.indexOf("**", i + 2)
        if (close > i + 2) {
          flushPlain()
          spans += MdSpan(text.substring(i + 2, close), bold = true)
          i = close + 2
          continue
        } else {
          plain.append("**")
          i += 2
          continue
        }
      } else if (text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ') {
        var close = text.indexOf('*', i + 1)
        while (close != -1 && text.startsWith("**", close)) {
          close = text.indexOf('*', close + 2)
        }
        if (close > i + 1) {
          flushPlain()
          spans += MdSpan(text.substring(i + 1, close), italic = true)
          i = close + 1
          continue
        }
      }
      plain.append(text[i])
      i++
    }
    flushPlain()
    return spans
  }
}
