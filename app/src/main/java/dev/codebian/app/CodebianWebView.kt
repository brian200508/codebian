package dev.codebian.app

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/**
 * WebView subclass that lets the sticky CTRL/ALT extra-keys toggles apply to
 * *any* character typed via the on-screen (soft) keyboard, not just the
 * dedicated one-shot buttons in the extra-keys row.
 *
 * Why this is necessary: soft keyboards (Gboard etc.) do not send individual
 * android.view.KeyEvent objects for regular letters/digits -- they commit
 * whole strings directly through the page's InputConnection, bypassing
 * Activity.dispatchKeyEvent (and therefore KeyBridge's hardware-key path)
 * entirely. Verified on-device: holding the CTRL toggle and typing "l" via
 * the soft keyboard inserted a literal "l" instead of sending Ctrl+L to the
 * terminal. We fix this by wrapping the InputConnection WebView creates for
 * the page's focused editable element, intercepting single-character commits
 * while a sticky modifier is held, and dispatching the equivalent synthetic
 * KeyboardEvent through KeyBridge instead of letting the character be typed
 * literally.
 */
class CodebianWebView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {

    @Volatile var ctrlHeld = false
    @Volatile var altHeld = false
    @Volatile var shiftHeld = false

    /**
     * Invoked after a sticky modifier has been consumed by a single typed
     * character, so ExtraKeysBar's toggle buttons can visually release
     * themselves -- matching the one-shot release behavior of the bar's own
     * dedicated buttons.
     */
    var onModifierConsumed: (() -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val hasModifier = ctrlHeld || altHeld
                if (hasModifier && text != null && text.length == 1) {
                    val ch = text[0]
                    val ctrl = ctrlHeld
                    val alt = altHeld
                    val shift = shiftHeld
                    ctrlHeld = false
                    altHeld = false
                    shiftHeld = false
                    // commitText() runs on the IME's own
                    // InputConnectionHandlerThread, NOT the main thread --
                    // but WebView.evaluateJavascript() (called deep inside
                    // KeyBridge.dispatch) asserts it is only ever called
                    // from the thread the WebView was created on. Calling
                    // it directly here crashes with "A WebView method was
                    // called on thread 'InputConnectionHandlerThread'"
                    // (confirmed on-device). Must hop back to the main
                    // looper first.
                    post {
                        KeyBridge.sendCharacterWithModifiers(
                            this@CodebianWebView, ch, ctrl = ctrl, alt = alt, shift = shift,
                        )
                        onModifierConsumed?.invoke()
                    }
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }
        }
    }
}
