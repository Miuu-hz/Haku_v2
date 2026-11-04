package com.bbpillow.haku.surface.chat

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

sealed interface MdBlock {
  data class Heading(val spans: List<MdSpan>) : MdBlock
  data class Paragraph(val spans: List<MdSpan>) : MdBlock
  /** [marker] is "•" for a bullet or "3." for a numbered item; [depth] 0 for top level, 1 for a nested one. */
  data class ListItem(val marker: String, val depth: Int, val spans: List<MdSpan>) : MdBlock
  data class Code(val text: String) : MdBlock
  data object Divider : MdBlock
}

object ChatMarkdown {
  private val HEADING = Regex("""^#{1,3} +(.*)$""")
  private val DIVIDER = Regex("""^\s*([-_*])(?:\s*\1){2,}\s*$""")
  private val BULLET = Regex("""^( *)([-*+]) +(.*)$""")
  private val NUMBERED = Regex("""^( *)(\d+\.) +(.*)$""")
  private val MATH_CMD = Regex("""\\[a-zA-Z%]""")
  private val DIGIT_OP = Regex("""\d\s*[*\/]|[*\/]\s*\d""")
  private val MATH_REPLACEMENTS = listOf(
    Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""") to "$1/$2",
    Regex("""\\sqrt\{([^{}]+)\}""") to "√$1",
    Regex("""\\text\{([^{}]+)\}""") to "$1",
    Regex("""\\times(?![a-zA-Z])""") to "×",
    Regex("""\\div(?![a-zA-Z])""") to "÷",
    Regex("""\\cdot(?![a-zA-Z])""") to "·",
    Regex("""\\pm(?![a-zA-Z])""") to "±",
    Regex("""\\leq(?![a-zA-Z])""") to "≤",
    Regex("""\\le(?![a-zA-Z])""") to "≤",
    Regex("""\\geq(?![a-zA-Z])""") to "≥",
    Regex("""\\ge(?![a-zA-Z])""") to "≥",
    Regex("""\\neq(?![a-zA-Z])""") to "≠",
    Regex("""\\approx(?![a-zA-Z])""") to "≈",
    Regex("""\\%""") to "%",
  )

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
        val d = DIVIDER.matches(line)
        val h = HEADING.matchEntire(line)
        val b = BULLET.matchEntire(line)
        val n = NUMBERED.matchEntire(line)
        when {
          d -> {
            flushPara()
            blocks += MdBlock.Divider
          }
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

  private fun parseSpans(raw: String): List<MdSpan> {
    if (raw.isEmpty()) return emptyList()
    val text = formatMath(raw)
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

  private fun formatMath(text: String): String {
    fun isMath(c: String, isDouble: Boolean): Boolean =
      c.isNotEmpty() &&
        (isDouble || (!c.startsWith(' ') && !c.endsWith(' '))) &&
        (MATH_CMD.containsMatchIn(c) || c.any { it in "=^+_×÷" } || DIGIT_OP.containsMatchIn(c))

    fun cleanMath(expr: String): String =
      MATH_REPLACEMENTS.fold(expr) { acc, (r, rep) -> r.replace(acc, rep) }

    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
      if (text[i] == '`') {
        val close = text.indexOf('`', i + 1)
        if (close != -1) {
          out.append(text, i, close + 1)
          i = close + 1
          continue
        }
      } else if (text.startsWith("$$", i)) {
        val close = text.indexOf("$$", i + 2)
        if (close != -1) {
          val inner = text.substring(i + 2, close)
          if (isMath(inner, isDouble = true)) {
            out.append(cleanMath(inner))
            i = close + 2
            continue
          }
        }
      } else if (text[i] == '$') {
        var close = -1
        var k = i + 1
        while (k < text.length) {
          if (text[k] == '$') {
            if (k + 1 < text.length && text[k + 1] == '$') {
              k += 2
              continue
            }
            close = k
            break
          }
          k++
        }
        if (close != -1) {
          val inner = text.substring(i + 1, close)
          if (isMath(inner, isDouble = false)) {
            out.append(cleanMath(inner))
            i = close + 1
            continue
          }
        }
      }
      out.append(text[i])
      i++
    }
    return out.toString()
  }
}
