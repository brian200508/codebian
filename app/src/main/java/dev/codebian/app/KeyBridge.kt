package dev.codebian.app

import android.view.KeyEvent
import android.webkit.WebView
import org.json.JSONObject

/**
 * Bridges Android key input (both the physical-keyboard path via
 * Activity.dispatchKeyEvent and the on-screen extra-keys row) into the
 * code-server / Monaco web page running inside our own WebView.
 *
 * Why this exists: a generic mobile browser tab can't be trusted to deliver
 * Ctrl/Alt/Esc/Tab combos to the page (browser chrome intercepts them for
 * tab-switching, "go back", focus navigation, etc.), and code-server itself
 * has no on-screen key overlay. Because this WebView is *ours* -- not system
 * Chrome -- we fully control dispatchKeyEvent and can synthesize the
 * equivalent DOM KeyboardEvent ourselves, with correct modifier flags, and
 * fire it at document.activeElement so Monaco's key bindings see it exactly
 * like a real keydown/keyup pair.
 */
object KeyBridge {

    /** label -> (DOM `key`, DOM `code`, legacy `keyCode`) for the extra-keys row. */
    private val SPECIAL_KEYS: Map<String, Triple<String, String, Int>> = mapOf(
        "ESC" to Triple("Escape", "Escape", 27),
        "TAB" to Triple("Tab", "Tab", 9),
        "ENTER" to Triple("Enter", "Enter", 13),
        "BKSP" to Triple("Backspace", "Backspace", 8),
        "DEL" to Triple("Delete", "Delete", 46),
        "UP" to Triple("ArrowUp", "ArrowUp", 38),
        "DOWN" to Triple("ArrowDown", "ArrowDown", 40),
        "LEFT" to Triple("ArrowLeft", "ArrowLeft", 37),
        "RIGHT" to Triple("ArrowRight", "ArrowRight", 39),
        "HOME" to Triple("Home", "Home", 36),
        "END" to Triple("End", "End", 35),
        "PGUP" to Triple("PageUp", "PageUp", 33),
        "PGDN" to Triple("PageDown", "PageDown", 34),
    )

    /**
     * Sends a synthetic keydown+keyup pair for [label] (one of [SPECIAL_KEYS]'
     * keys) with the given modifier state, as tapped from the on-screen
     * extra-keys row.
     */
    fun sendSpecialKey(
        webView: WebView,
        label: String,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) {
        val (key, code, keyCode) = SPECIAL_KEYS[label] ?: return
        dispatch(webView, key, code, keyCode, ctrl, alt, shift, meta)
    }

    /**
     * Sends a synthetic keydown+keyup for a single typed character combined
     * with sticky CTRL/ALT/SHIFT modifiers -- used when the soft keyboard
     * commits a character while a modifier toggle is held (see
     * CodebianWebView.onCreateInputConnection). Digits/letters map to their
     * standard DOM `code` (KeyX / DigitN); anything else falls back to a
     * best-effort code derived from the character itself.
     */
    fun sendCharacterWithModifiers(
        webView: WebView,
        ch: Char,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) {
        val lower = ch.lowercaseChar()
        val (code, keyCode) = when {
            lower in 'a'..'z' -> "Key${lower.uppercaseChar()}" to lower.uppercaseChar().code
            lower in '0'..'9' -> "Digit$lower" to '0'.code + (lower - '0')
            else -> "Key${lower.uppercaseChar()}" to lower.uppercaseChar().code
        }
        dispatch(webView, lower.toString(), code, keyCode, ctrl, alt, shift, meta = false)
    }

    /**
     * Handles a physical-keyboard [KeyEvent] that Android's own focus system
     * would otherwise swallow or mis-deliver (Escape/Tab are notorious for
     * being consumed by view focus navigation instead of reaching the
     * WebView). Returns true if the event was forwarded and should be
     * considered consumed.
     */
    fun forwardHardwareKeyEvent(webView: WebView, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val (key, code, jsKeyCode) = androidKeyCodeToJs(event.keyCode) ?: return false
        dispatch(
            webView, key, code, jsKeyCode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            meta = event.isMetaPressed,
        )
        return true
    }

    /** Only intercept the keys Android is likely to steal for its own UI. */
    fun shouldInterceptHardwareKey(event: KeyEvent): Boolean {
        val hasModifier = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB -> true
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> hasModifier
            else -> hasModifier
        }
    }

    /**
     * True if [keyCode] is a plain printable letter/digit that
     * [androidKeyCodeToJs] knows how to map. Used by MainActivity to also
     * honor the extra-keys row's *sticky* CTRL/ALT toggle when a soft
     * keyboard happens to deliver a plain (non-modified) KeyEvent for a
     * letter/digit instead of routing it through InputConnection.commitText
     * -- some IME configurations (and key-injection tools) do this, so we
     * can't rely solely on CodebianWebView's InputConnection interception.
     */
    fun isPrintableKey(keyCode: Int): Boolean = androidKeyCodeToJs(keyCode) != null

    /**
     * Forwards a plain letter/digit [KeyEvent] combined with the extra-keys
     * row's sticky modifier state, rather than the event's own (real)
     * modifier flags -- the sticky-toggle counterpart to
     * [forwardHardwareKeyEvent]. Returns true if forwarded.
     */
    fun forwardWithStickyModifiers(
        webView: WebView,
        event: KeyEvent,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val (key, code, jsKeyCode) = androidKeyCodeToJs(event.keyCode) ?: return false
        dispatch(webView, key, code, jsKeyCode, ctrl, alt, shift, meta = false)
        return true
    }

    private fun androidKeyCodeToJs(keyCode: Int): Triple<String, String, Int>? = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> Triple("Escape", "Escape", 27)
        KeyEvent.KEYCODE_TAB -> Triple("Tab", "Tab", 9)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Triple("Enter", "Enter", 13)
        KeyEvent.KEYCODE_DEL -> Triple("Backspace", "Backspace", 8)
        KeyEvent.KEYCODE_FORWARD_DEL -> Triple("Delete", "Delete", 46)
        KeyEvent.KEYCODE_DPAD_UP -> Triple("ArrowUp", "ArrowUp", 38)
        KeyEvent.KEYCODE_DPAD_DOWN -> Triple("ArrowDown", "ArrowDown", 40)
        KeyEvent.KEYCODE_DPAD_LEFT -> Triple("ArrowLeft", "ArrowLeft", 37)
        KeyEvent.KEYCODE_DPAD_RIGHT -> Triple("ArrowRight", "ArrowRight", 39)
        KeyEvent.KEYCODE_MOVE_HOME -> Triple("Home", "Home", 36)
        KeyEvent.KEYCODE_MOVE_END -> Triple("End", "End", 35)
        KeyEvent.KEYCODE_PAGE_UP -> Triple("PageUp", "PageUp", 33)
        KeyEvent.KEYCODE_PAGE_DOWN -> Triple("PageDown", "PageDown", 34)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
            val ch = ('a' + (keyCode - KeyEvent.KEYCODE_A))
            Triple(ch.toString(), "Key" + ch.uppercaseChar(), ch.uppercaseChar().code)
        }
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
            val digit = keyCode - KeyEvent.KEYCODE_0
            Triple(digit.toString(), "Digit$digit", '0'.code + digit)
        }
        else -> null
    }

    private fun dispatch(
        webView: WebView,
        key: String,
        code: String,
        keyCode: Int,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        meta: Boolean,
    ) {
        // JSONObject.quote() takes care of escaping so `key`/`code` can never
        // break out of the JS string literals below.
        val keyJson = JSONObject.quote(key)
        val codeJson = JSONObject.quote(code)
        val js = """
            (function() {
              var target = document.activeElement || document.body;
              ['keydown', 'keyup'].forEach(function(type) {
                var ev = new KeyboardEvent(type, {
                  key: $keyJson,
                  code: $codeJson,
                  keyCode: $keyCode,
                  which: $keyCode,
                  ctrlKey: $ctrl,
                  altKey: $alt,
                  shiftKey: $shift,
                  metaKey: $meta,
                  bubbles: true,
                  cancelable: true,
                });
                target.dispatchEvent(ev);
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
