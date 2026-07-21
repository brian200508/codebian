package dev.codebian.app

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Builds the Termux-style extra-keys row: sticky CTRL/ALT toggles plus
 * one-shot ESC/TAB/arrow/navigation buttons. Tapping a one-shot key sends it
 * combined with whichever sticky modifiers are currently held, then releases
 * them -- the same UX Termux users are already familiar with from
 * `~/.termux/termux.properties`' `extra-keys`.
 *
 * The sticky modifier state itself lives on [webView] (a [CodebianWebView]),
 * not in this class, so that CodebianWebView's InputConnection wrapper can
 * also honor CTRL/ALT held while the user types a regular character on the
 * soft keyboard (see CodebianWebView for why that's necessary) -- this class
 * and the WebView's own input interception share one single source of truth
 * for "is CTRL/ALT/SHIFT currently held".
 */
class ExtraKeysBar(private val container: LinearLayout, private val webView: CodebianWebView) {

    private lateinit var ctrlToggle: ToggleButton
    private lateinit var altToggle: ToggleButton
    private lateinit var shiftToggle: ToggleButton
    private var size: KeysBarSize = KeysBarSize.MEDIUM_PLUS
    private var keyWidth: KeyWidth = KeyWidth.FULL

    /** Persists [newSize] and rebuilds the row's buttons at the new dimensions. */
    fun applySize(newSize: KeysBarSize) {
        AppPreferences.setKeysBarSize(container.context, newSize)
        build()
    }

    /** Persists [newWidth] and rebuilds the row's buttons at the new width scale. */
    fun applyKeyWidth(newWidth: KeyWidth) {
        AppPreferences.setKeysBarKeyWidth(container.context, newWidth)
        build()
    }

    fun build() {
        val ctx = container.context
        size = AppPreferences.getKeysBarSize(ctx)
        keyWidth = AppPreferences.getKeysBarKeyWidth(ctx)
        container.removeAllViews()

        commandPaletteButton(ctx)

        // Order requested by the user: command palette, then ESC, then
        // SHIFT (i.e. ESC sits right behind the command palette button,
        // and SHIFT sits right behind ESC), followed by CTRL/ALT and TAB.
        oneShot(ctx, "ESC")

        ctrlToggle = stickyToggle(ctx, "CTRL") { webView.ctrlHeld = it }
        altToggle = stickyToggle(ctx, "ALT") { webView.altHeld = it }
        shiftToggle = stickyToggle(ctx, "SHIFT") { webView.shiftHeld = it }

        container.addView(shiftToggle)
        container.addView(ctrlToggle)
        container.addView(altToggle)

        oneShot(ctx, "TAB")
        oneShot(ctx, "LEFT", "\u2190")
        oneShot(ctx, "UP", "\u2191")
        oneShot(ctx, "DOWN", "\u2193")
        oneShot(ctx, "RIGHT", "\u2192")
        oneShot(ctx, "HOME")
        oneShot(ctx, "END")
        oneShot(ctx, "PGUP")
        oneShot(ctx, "PGDN")
        oneShot(ctx, "BKSP")
        oneShot(ctx, "DEL")
        oneShot(ctx, "ENTER")
        keyboardToggleButton(ctx)

        // Keep the toggle buttons visually in sync when CodebianWebView
        // itself releases the sticky modifiers after they were consumed by
        // a soft-keyboard-typed character (e.g. holding CTRL then typing
        // "c" on Gboard to send Ctrl+C).
        webView.onModifierConsumed = { releaseModifiers() }
    }

    private fun stickyToggle(ctx: Context, label: String, onChanged: (Boolean) -> Unit): ToggleButton {
        return ToggleButton(ctx).apply {
            textOn = label
            textOff = label
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            textSize = size.textSp
            val h = dp(ctx, size.paddingHDp)
            val v = dp(ctx, size.paddingVDp)
            setPadding(h, v, h, v)
            background = keyButtonBackground(ctx)
            setTextColor(themeColor(ctx, R.attr.colorKeyFg))
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }.also { finalizeKeyButtonSize(ctx, it) }
    }

    private fun oneShot(ctx: Context, label: String, displayText: String = label) {
        val button = Button(ctx).apply {
            text = displayText
            isAllCaps = false
            textSize = size.textSp
            val h = dp(ctx, size.paddingHDp)
            val v = dp(ctx, size.paddingVDp)
            setPadding(h, v, h, v)
            background = keyButtonBackground(ctx)
            setTextColor(themeColor(ctx, R.attr.colorKeyFg))
            setOnClickListener {
                KeyBridge.sendSpecialKey(
                    webView, label,
                    ctrl = webView.ctrlHeld, alt = webView.altHeld, shift = webView.shiftHeld,
                )
                // One-shot release, matching Termux's extra-keys behaviour:
                // modifiers apply to exactly the next key press.
                releaseModifiers()
            }
        }
        finalizeKeyButtonSize(ctx, button)
        container.addView(button)
    }

    private fun releaseModifiers() {
        webView.ctrlHeld = false
        webView.altHeld = false
        webView.shiftHeld = false
        ctrlToggle.isChecked = false
        altToggle.isChecked = false
        shiftToggle.isChecked = false
    }

    /**
     * Fixed Ctrl+Shift+P combo -- VS Code's Command Palette shortcut -- sent
     * directly regardless of the sticky CTRL/ALT/SHIFT toggle state (it
     * doesn't read or clear them), since this is a single well-known,
     * frequently-needed combo that deserves its own one-tap shortcut rather
     * than requiring the user to hold two sticky modifiers themselves.
     * Placed as the very first (leftmost) button in the row per user
     * request, ahead of the CTRL/ALT/SHIFT toggles.
     */
    private fun commandPaletteButton(ctx: Context) {
        val button = Button(ctx).apply {
            text = "\u2318\u21e7P"
            isAllCaps = false
            textSize = size.textSp
            val h = dp(ctx, size.paddingHDp)
            val v = dp(ctx, size.paddingVDp)
            setPadding(h, v, h, v)
            background = keyButtonBackground(ctx)
            setTextColor(themeColor(ctx, R.attr.colorKeyFg))
            setOnClickListener {
                KeyBridge.sendCharacterWithModifiers(webView, 'p', ctrl = true, alt = false, shift = true)
            }
        }
        finalizeKeyButtonSize(ctx, button)
        container.addView(button)
    }

    /**
     * Dedicated always-visible keyboard show/hide toggle, using the modern
     * WindowInsetsControllerCompat API (the same non-hack mechanism already
     * used for the fullscreen system-bars toggle) rather than the older
     * InputMethodManager.toggleSoftInput()/showSoftInput() calls, which are
     * less reliable about tracking actual IME visibility state.
     */
    private fun keyboardToggleButton(ctx: Context) {
        val button = Button(ctx).apply {
            text = "KBD"
            isAllCaps = false
            textSize = size.textSp
            val h = dp(ctx, size.paddingHDp)
            val v = dp(ctx, size.paddingVDp)
            setPadding(h, v, h, v)
            background = keyButtonBackground(ctx)
            setTextColor(themeColor(ctx, R.attr.colorKeyFg))
            setOnClickListener {
                val activity = webView.context as? android.app.Activity ?: return@setOnClickListener
                val controller = WindowCompat.getInsetsController(activity.window, webView)
                val imeVisible = ViewCompat.getRootWindowInsets(webView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
                if (imeVisible) {
                    controller.hide(WindowInsetsCompat.Type.ime())
                } else {
                    webView.requestFocus()
                    controller.show(WindowInsetsCompat.Type.ime())
                }
            }
        }
        finalizeKeyButtonSize(ctx, button)
        container.addView(button)
    }

    /**
     * Measures [view]'s natural WRAP_CONTENT width (i.e. exactly what it
     * would render at today's default 1.00 factor, including the Material
     * button style's own built-in minWidth -- a plain View.minimumWidth
     * override can't shrink below that floor, which is why an earlier
     * attempt at width scaling via minimumWidth had no visible effect at
     * 0.50/0.25), then -- unless keyWidth is FULL -- sets an explicit
     * fractional LayoutParams.width so the button actually renders narrower
     * (short labels shrink cleanly; very long labels like "PGUP"/"ENTER" may
     * get visually tight at 0.25, which is an accepted tradeoff of shrinking
     * below their natural text width).
     */
    private fun finalizeKeyButtonSize(ctx: Context, view: TextView) {
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val naturalWidth = view.measuredWidth
        val width = if (keyWidth == KeyWidth.FULL) {
            LinearLayout.LayoutParams.WRAP_CONTENT
        } else {
            (naturalWidth * keyWidth.factor).toInt().coerceAtLeast(dp(ctx, 24))
        }
        view.layoutParams = LinearLayout.LayoutParams(width, dp(ctx, size.heightDp)).apply {
            marginEnd = dp(ctx, 4)
        }
    }

    private fun dp(ctx: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /** Resolves a theme color attribute (e.g. R.attr.colorKeyFg) to its current int value. */
    private fun themeColor(ctx: Context, attrResId: Int): Int {
        val typedValue = TypedValue()
        ctx.theme.resolveAttribute(attrResId, typedValue, true)
        return typedValue.data
    }

    /**
     * Built programmatically (rather than via drawable/key_button_bg.xml's
     * former "?attr/colorKeyBg" selector) because on-device this crashed
     * with UnsupportedOperationException: "Failed to resolve attribute...
     * theme=null" -- Context.getDrawable()/AppCompatResources.getDrawable()
     * both bottom out in a Resources.getDrawableForDensity() overload that
     * doesn't propagate a Theme when inflating a <selector> containing
     * "?attr/" item references, so theme-attribute-in-drawable-XML doesn't
     * reliably work here. TypedValue-based themeColor() resolution (used
     * for text color already) doesn't hit that code path and is reliable,
     * so we reuse it here and build the StateListDrawable ourselves.
     */
    private fun keyButtonBackground(ctx: Context): StateListDrawable {
        val active = themeColor(ctx, R.attr.colorKeyBgActive)
        val normal = themeColor(ctx, R.attr.colorKeyBg)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), ColorDrawable(active))
            addState(intArrayOf(), ColorDrawable(normal))
        }
    }
}

