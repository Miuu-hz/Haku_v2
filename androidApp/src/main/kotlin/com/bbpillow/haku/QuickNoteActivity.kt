package com.bbpillow.haku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bbpillow.haku.platform.widget.HakuWidget
import com.bbpillow.haku.surface.note.QuickNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P3.5 quick note: one line in, saved as today's user note, then gone. Shown over the lock screen (showWhenLocked) so a
 * thought takes seconds, which is why it is input-only: it never reads or shows the diary and has no way into the app.
 * Own empty task affinity + excluded from recents, so finishing it never brings the app's task forward.
 */
class QuickNoteActivity : Activity() {
  private lateinit var input: EditText
  private lateinit var save: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val pad = (16 * resources.displayMetrics.density).toInt()
    input = EditText(this).apply {
      hint = "คิดอะไรอยู่ จดไว้ได้เลย"
      isSingleLine = true
      imeOptions = EditorInfo.IME_ACTION_DONE
      filters = arrayOf(InputFilter.LengthFilter(QuickNote.MAX_CHARS))
      setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit() } }
    }
    save = Button(this).apply {
      text = "บันทึก"
      setOnClickListener { submit() }
    }
    val title = TextView(this).apply {
      text = "บันทึกด่วน"
      textSize = 18f
    }
    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad / 2)
        addView(title)
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
      },
    )
    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    input.requestFocus() // the keyboard comes up with the window (windowSoftInputMode=stateAlwaysVisible)
  }

  /** Blank = just close. Otherwise stay open until it is saved, so a failed write never loses the typed line. */
  private fun submit() {
    val text = input.text.toString()
    if (QuickNote.clean(text) == null) return finish()
    input.isEnabled = false
    save.isEnabled = false
    val app = applicationContext
    (application as HakuApplication).graph.scope.launch {
      val ok = HakuWidget.saveNote(app, text)
      withContext(Dispatchers.Main) {
        Toast.makeText(app, if (ok) "จดแล้ว" else "จดไม่สำเร็จ ลองอีกครั้ง", Toast.LENGTH_SHORT).show()
        if (ok) {
          finish()
        } else if (!isDestroyed) {
          input.isEnabled = true
          save.isEnabled = true
        }
      }
    }
  }

  companion object {
    fun intent(context: Context): Intent =
      Intent(context, QuickNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }
}
