package dev.codebian.app

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * Drives the single persistent floating overlay button that is the entry
 * point for all secondary UI toggles (keys-bar show/hide, fullscreen,
 * keys-bar size). It's draggable so it can be moved out of the way of
 * on-screen editor content, and it remains visible even when the keys bar
 * and/or system bars are hidden -- which is why those toggles hang off this
 * button rather than living in the keys bar itself.
 */
class FloatingMenuButton(
    private val activity: AppCompatActivity,
    private val root: FrameLayout,
    private val button: ImageButton,
    private val keyBarScroll: View,
    private val extraKeysBar: ExtraKeysBar,
    private val safActions: SafActions,
    private val configBackupActions: ConfigBackupActions,
) {
    private var downRawX = 0f
    private var downRawY = 0f
    private var downMarginLeft = 0
    private var downMarginTop = 0
    private var dragged = false
    private var longPressTriggered = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showQuickActionsMenu()
    }

    // Root is edge-to-edge (see MainActivity.setupExtraKeysBarInsets), so the
    // button's own bottom-right default position must stay clear of the
    // navigation bar / gesture inset itself -- otherwise it renders (mostly)
    // underneath the system nav bar, as verified on-device. MainActivity's
    // single WindowInsets listener on `root` calls updateNavBottomInset()
    // rather than this class installing its own listener on the same view
    // (which would silently replace MainActivity's status-bar padding one).
    private var navBottomInset = 0
    private var defaultMarginPx = 0

    fun updateNavBottomInset(px: Int) {
        navBottomInset = px
        if (AppPreferences.getFabPosition(activity) == null) {
            root.post { applyDefaultPosition() }
        }
    }

    fun setup() {
        defaultMarginPx = (16 * activity.resources.displayMetrics.density).toInt()
        restorePosition()
        applyKeysBarVisibility()
        applyFullscreen()

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragged = false
                    longPressTriggered = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val lp = view.layoutParams as FrameLayout.LayoutParams
                    downMarginLeft = lp.leftMargin
                    downMarginTop = lp.topMargin
                    longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > TAP_SLOP_PX || abs(dy) > TAP_SLOP_PX) {
                        dragged = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        moveTo(downMarginLeft + dx.toInt(), downMarginTop + dy.toInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (dragged) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams
                        AppPreferences.setFabPosition(activity, lp.leftMargin, lp.topMargin)
                    } else if (!longPressTriggered) {
                        // The long-press quick-actions popup already fired
                        // on its own timer; don't also show it a second
                        // time once the finger lifts. A plain tap opens the
                        // very same popup -- it's the primary entry point
                        // for this button now, with the full Settings
                        // dialog reachable via its own "Config" item.
                        showQuickActionsMenu()
                    }
                    true
                }
                else -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    false
                }
            }
        }
    }

    private fun restorePosition() {
        val saved = AppPreferences.getFabPosition(activity)
        root.post {
            if (saved != null) moveTo(saved.first, saved.second) else applyDefaultPosition()
        }
    }

    /** Bottom-right corner, clear of the navigation bar / gesture inset. */
    private fun applyDefaultPosition() {
        val left = (root.width - button.width - defaultMarginPx).coerceAtLeast(0)
        val top = (root.height - button.height - defaultMarginPx - navBottomInset).coerceAtLeast(0)
        moveTo(left, top)
    }

    private fun moveTo(leftMarginRaw: Int, topMarginRaw: Int) {
        val lp = button.layoutParams as FrameLayout.LayoutParams
        val maxLeft = (root.width - button.width).coerceAtLeast(0)
        val maxTop = (root.height - button.height - navBottomInset).coerceAtLeast(0)
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = leftMarginRaw.coerceIn(0, maxLeft)
        lp.topMargin = topMarginRaw.coerceIn(0, maxTop)
        button.layoutParams = lp
    }

    /** Opens the consolidated Settings dialog; also called by MainActivity right after a theme-preview recreate(). */
    fun showSettingsDialog() {
        SettingsDialog(
            activity = activity,
            extraKeysBar = extraKeysBar,
            configBackupActions = configBackupActions,
            applyFullscreen = { applyFullscreen() },
            applyKeysBarVisibility = { applyKeysBarVisibility() },
        ).show()
    }

    private fun applyKeysBarVisibility() {
        keyBarScroll.visibility = if (AppPreferences.isKeysBarVisible(activity)) View.VISIBLE else View.GONE
    }

    /**
     * Primary entry point for the floating button (both a plain tap and a
     * long-press open this): Termux/SSH command/Fullscreen/Wake lock/Exit
     * shortcuts, a "Files" submenu (folder-workspace pick/sync-back, see
     * [SafActions]), plus a
     * "Config" item that opens the full Settings dialog for everything
     * else. SSH Password is intentionally not included here now that it
     * can be a custom user-chosen value (managed via Config > Remote
     * Access instead of a one-tap "copy the auto-generated one" action).
     * Unlike the Settings dialog's own copies of these actions,
     * Fullscreen/Wake lock here toggle and persist immediately (there's no
     * Save step for a one-tap popup action). Exit asks for confirmation
     * first (see [confirmExit]) since it stops running servers and closes
     * the app.
     */
    private fun showQuickActionsMenu() {
        val popup = PopupMenu(activity, button)
        popup.menuInflater.inflate(R.menu.floating_menu_button_popup, popup.menu)
        popup.menu.findItem(R.id.quickMenuFullscreen).isChecked = AppPreferences.isFullscreenEnabled(activity)
        popup.menu.findItem(R.id.quickMenuWakeLock).isChecked = AppPreferences.isWakeLockEnabled(activity)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.quickMenuTermux -> {
                    RemoteAccessActions.openTermux(activity)
                    true
                }
                R.id.quickMenuSshCommand -> {
                    RemoteAccessActions.copySshCommand(activity)
                    true
                }
                R.id.quickMenuFullscreen -> {
                    AppPreferences.setFullscreenEnabled(activity, !AppPreferences.isFullscreenEnabled(activity))
                    applyFullscreen()
                    true
                }
                R.id.quickMenuWakeLock -> {
                    val enabled = !AppPreferences.isWakeLockEnabled(activity)
                    AppPreferences.setWakeLockEnabled(activity, enabled)
                    BootstrapService.requestWakeLockSync(activity)
                    true
                }
                R.id.quickMenuConfig -> {
                    showSettingsDialog()
                    true
                }
                R.id.quickMenuPickWorkspaceFolder -> {
                    safActions.pickWorkspaceFolder()
                    true
                }
                R.id.quickMenuSyncWorkspaceBack -> {
                    safActions.syncWorkspaceBack()
                    true
                }
                R.id.quickMenuExit -> {
                    confirmExit()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * Confirmation popup for the Exit quick action -- defaults to Cancel
     * (requests focus on it right after showing) so an accidental tap on
     * the long-press popup's Exit item, or a stray Enter/D-pad press,
     * doesn't immediately tear down the running servers.
     */
    private fun confirmExit() {
        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_CoDebian_AlertDialog)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_message)
            .setPositiveButton(R.string.exit_confirm_positive) { _, _ -> performExit() }
            .setNegativeButton(R.string.exit_confirm_negative, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
        dialog.show()
    }

    /** Stops code-server/sshd, then closes the app -- the user chose Exit, so nothing should linger in the background. */
    private fun performExit() {
        BootstrapService.stopAllServers(activity)
        activity.finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Hides the system status/navigation bars (transient, swipe-to-reveal)
     * rather than a "lean back" immersive mode with no way back -- the user
     * can always swipe from an edge to briefly reveal the bars again, and
     * the floating button remains available regardless to toggle back off.
     */
    private fun applyFullscreen() {
        val controller = WindowInsetsControllerCompat(activity.window, root)
        if (AppPreferences.isFullscreenEnabled(activity)) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val TAP_SLOP_PX = 12
    }
}
