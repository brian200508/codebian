package dev.codebian.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Detects/launches the Termux app (package "com.termux") for the
 * "Open Termux" Settings-dialog action, so the user can paste the just-
 * copied SSH/SFTP command straight into a real terminal app on the same
 * device, without us needing our own SSH client or any Termux-side
 * RUN_COMMAND/allow-external-apps integration.
 *
 * Termux ships the exact same package name from Google Play, F-Droid, and
 * GitHub releases, but a device can only have one of those signing keys
 * installed at a time -- we don't assume any particular source, we just
 * detect whichever one (if any) is already present.
 */
object TermuxLauncher {
    private const val PACKAGE_NAME = "com.termux"
    private const val GITHUB_RELEASES_URL = "https://github.com/termux/termux-app/releases/latest"
    private const val POLL_INTERVAL_MS = 1500L
    private const val POLL_TIMEOUT_MS = 5 * 60_000L

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Opens Termux if already installed, otherwise offers Play Store /
     * GitHub install options and automatically launches Termux once it's
     * detected as installed (polling in the background while the user
     * installs it through their browser/Play Store, so they don't have to
     * manually return and re-tap "Open Termux").
     */
    fun openOrOfferInstall(activity: AppCompatActivity) {
        if (isInstalled(activity)) {
            launch(activity)
            return
        }
        AlertDialog.Builder(activity, R.style.ThemeOverlay_CoDebian_AlertDialog)
            .setTitle(R.string.termux_install_dialog_title)
            .setMessage(R.string.termux_install_dialog_message)
            .setPositiveButton(R.string.termux_install_play_store) { _, _ ->
                openPlayStore(activity)
                waitForInstallThenLaunch(activity)
            }
            .setNegativeButton(R.string.termux_install_github) { _, _ ->
                openGithubReleases(activity)
                waitForInstallThenLaunch(activity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
        if (intent != null) {
            context.startActivity(intent)
        }
    }

    private fun openPlayStore(context: Context) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE_NAME"))
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE_NAME"),
                )
            )
        }
    }

    private fun openGithubReleases(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL)))
    }

    /**
     * Polls [isInstalled] every [POLL_INTERVAL_MS] for up to
     * [POLL_TIMEOUT_MS] (the user needs time to find/download/tap-install
     * the APK in their browser/Play Store) and auto-launches Termux the
     * moment it appears, so the whole "install then open" flow doesn't
     * require the user to manually come back and press anything else.
     */
    private fun waitForInstallThenLaunch(activity: AppCompatActivity) {
        val handler = Handler(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        lateinit var check: () -> Unit
        check = {
            when {
                isInstalled(activity) -> launch(activity)
                System.currentTimeMillis() < deadline -> handler.postDelayed(check, POLL_INTERVAL_MS)
                else -> Unit // gave up silently; user can just tap "Open Termux" again later
            }
        }
        handler.postDelayed(check, POLL_INTERVAL_MS)
    }
}
