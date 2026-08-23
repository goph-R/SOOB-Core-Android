package info.dynart.soob

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Soft-keyboard bridge — the port of SOOB-Core-Web's `src/host/ime.ts`.
 *
 * The `lineEdit` widget (SOOB-Core `scripts/engine/widget.lua`) renders its own
 * text and caret and only consumes `onTextInput` / `onKeyDown("backspace")`
 * while focused, so all this has to do is summon the OS keyboard and forward
 * characters: an invisible [EditText] takes focus, and its edits are diffed
 * into the same hooks a physical keyboard would fire.
 *
 * `imeShow`/`imeHide` arrive on the GL thread; every view touch here is posted
 * to the UI thread, and every hook call is queued back onto the GL thread.
 */
object Ime {

    private var activity: SoobActivity? = null
    private var edit: EditText? = null
    private var active = false
    private var prev = ""
    private var suppress = false

    fun attach(a: SoobActivity, root: FrameLayout) {
        activity = a

        val el = EditText(a)
        el.setSingleLine(true)
        el.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD   // no autocorrect / autocapitalize
        el.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        el.isCursorVisible = false
        el.alpha = 0f                                        // invisible, still focusable
        el.setBackgroundColor(0)

        // Diff the field on every edit: robust across keyboards where key
        // events for printable characters are unreliable. Caret is assumed at
        // the end (single-line name entry), exactly like the web bridge.
        el.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppress) return
                val v = s?.toString() ?: ""
                var i = 0
                val min = minOf(v.length, prev.length)
                while (i < min && v[i] == prev[i]) i++
                val backspaces = prev.length - i
                val typed = if (i < v.length) v.substring(i) else ""
                prev = v
                activity?.queueGl {
                    repeat(backspaces) { Lua.keyDown("backspace") }
                    for (ch in typed) Lua.textInput(ch.toString())
                }
            }
        })

        el.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                activity?.queueGl { Lua.keyDown("return") }
                hide()
                true
            } else {
                false
            }
        }

        val lp = FrameLayout.LayoutParams(1, 1)
        lp.gravity = Gravity.TOP or Gravity.START
        root.addView(el, lp)
        edit = el
    }

    fun isActive(): Boolean = active

    /** Called by `lineEdit` on focus-in; bbox is in virtual-canvas coords. */
    fun show(x: Double, y: Double, w: Double, h: Double) {
        val a = activity ?: return
        val vx = x.toFloat()
        val vy = y.toFloat()
        a.runOnUiThread {
            val el = edit ?: return@runOnUiThread
            val view = a.gameView
            val px = Renderer.viewportLeft() + ((vx / Renderer.viewW()) + 0.5f) * Renderer.viewportW()
            val py = Renderer.viewportTop() + ((vy / Renderer.viewH()) + 0.5f) * Renderer.viewportH()
            (el.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = px.toInt().coerceIn(0, maxOf(0, view.width - 1))
                lp.topMargin = py.toInt().coerceIn(0, maxOf(0, view.height - 1))
                el.layoutParams = lp
            }
            suppress = true
            el.setText("")
            suppress = false
            prev = ""
            active = true
            el.isFocusableInTouchMode = true
            el.requestFocus()
            val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(el, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Called by `lineEdit` on focus-out (and by the IME's Done action). */
    fun hide() {
        val a = activity ?: return
        active = false
        a.runOnUiThread {
            val el = edit ?: return@runOnUiThread
            val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(el.windowToken, 0)
            suppress = true
            el.setText("")
            suppress = false
            prev = ""
            el.clearFocus()
            a.gameView.requestFocus()
        }
    }
}
