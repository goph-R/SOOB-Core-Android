package net.dynart.soob

import android.view.KeyEvent

/**
 * Input state — the port of SOOB-Core-Web's `src/host/input.ts`.
 *
 * Holds what the polling bindings read (`keyDown`, `mousePos`, `mouseDown`,
 * `keyModifiers`) and maps Android key codes to the SDL lowercase key names
 * the Lua scripts expect (`"space"`, `"escape"`, `"left"`, `"a"`, `"1"`,
 * `"f1"`, `"return"`, …), so a physical keyboard or gamepad behaves the same
 * as on desktop.
 *
 * Written only from GL-thread runnables (see [GameView], which queues every
 * UI-thread event), so no synchronization is needed.
 */
object Input {

    private val held = HashSet<String>()
    private val buttons = HashSet<Int>()

    var mouseX = 0f
        private set
    var mouseY = 0f
        private set

    private var shift = false
    private var ctrl = false
    private var alt = false

    fun isKeyDown(name: String): Boolean = held.contains(name)

    fun isButtonDown(b: Int): Boolean = buttons.contains(b)

    fun modifiers(): Int = (if (shift) 1 else 0) or (if (ctrl) 2 else 0) or (if (alt) 4 else 0)

    fun setMouse(x: Float, y: Float) {
        mouseX = x
        mouseY = y
    }

    fun pressButton(b: Int) {
        buttons.add(b)
    }

    fun releaseButton(b: Int) {
        buttons.remove(b)
    }

    fun pressKey(name: String) {
        held.add(name)
    }

    fun releaseKey(name: String) {
        held.remove(name)
    }

    fun setModifiers(event: KeyEvent) {
        shift = event.isShiftPressed
        ctrl = event.isCtrlPressed
        alt = event.isAltPressed
    }

    fun clearHeld() {
        held.clear()
        buttons.clear()
    }

    /** Android key code → the SDL lowercase name used throughout the Lua. */
    fun keyName(event: KeyEvent): String {
        when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> return "space"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return "return"
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> return "escape"
            KeyEvent.KEYCODE_DEL -> return "backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> return "delete"
            KeyEvent.KEYCODE_TAB -> return "tab"
            KeyEvent.KEYCODE_DPAD_LEFT -> return "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> return "right"
            KeyEvent.KEYCODE_DPAD_UP -> return "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> return "down"
            KeyEvent.KEYCODE_DPAD_CENTER -> return "return"
            KeyEvent.KEYCODE_MOVE_HOME -> return "home"
            KeyEvent.KEYCODE_MOVE_END -> return "end"
            KeyEvent.KEYCODE_PAGE_UP -> return "pageup"
            KeyEvent.KEYCODE_PAGE_DOWN -> return "pagedown"
            KeyEvent.KEYCODE_SHIFT_LEFT -> return "left shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return "right shift"
            KeyEvent.KEYCODE_CTRL_LEFT -> return "left ctrl"
            KeyEvent.KEYCODE_CTRL_RIGHT -> return "right ctrl"
            KeyEvent.KEYCODE_ALT_LEFT -> return "left alt"
            KeyEvent.KEYCODE_ALT_RIGHT -> return "right alt"
        }
        if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return "f" + (event.keyCode - KeyEvent.KEYCODE_F1 + 1)
        }
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (event.keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        if (event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return ('a' + (event.keyCode - KeyEvent.KEYCODE_A)).toString()
        }
        // Fall back to the printable character the key produces, unshifted.
        val ch = event.getUnicodeChar(0)
        if (ch != 0) return ch.toChar().lowercaseChar().toString()
        return KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").lowercase()
    }
}
