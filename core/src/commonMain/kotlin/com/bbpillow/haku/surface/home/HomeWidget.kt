package com.bbpillow.haku.surface.home

/** What capture is doing right now, for the widget's status line (P3.5). */
enum class CaptureStatus { OFF, MOVING, STAYING }

/** The home-screen widget's text (P3.5). [body]/[status] null = that row is hidden. */
data class HomeWidgetOutput(val title: String, val body: String?, val status: String?)

object HomeWidget {
  const val BARE_TITLE = "Haku"
  const val BODY_LINES = 2

  /**
   * The widget sits outside the app lock (D11): with the lock on it shows no diary text and no status, only "Haku" and
   * the quick-note button. [brief] null (not onboarded, lock on, or the load failed) gives the same bare widget.
   */
  fun content(brief: HomeBriefUiState?, lockEnabled: Boolean, status: CaptureStatus): HomeWidgetOutput {
    if (lockEnabled || brief == null) return HomeWidgetOutput(BARE_TITLE, null, null)
    val body = brief.storySummary ?: brief.storyLines.take(BODY_LINES).joinToString("\n").ifEmpty { brief.headline }
    return HomeWidgetOutput(brief.storyTitle, body, statusText(status))
  }

  fun statusText(status: CaptureStatus): String = when (status) {
    CaptureStatus.OFF -> "การบันทึกปิดอยู่"
    CaptureStatus.MOVING -> "● กำลังบันทึกการเดินทาง"
    CaptureStatus.STAYING -> "● อยู่กับที่ Haku กำลังจดเวลาไว้"
  }
}
