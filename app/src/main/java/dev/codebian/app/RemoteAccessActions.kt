package dev.codebian.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Copy-SSH-command / copy-SSH-password / open-Termux actions, shared
 * between the Settings dialog's Remote Access section and the floating
 * menu button's long-press quick-actions popup, so both entry points stay
 * in sync (same clipboard label, same toast wording, same live SSH
 * port/user lookup) without duplicating the logic in two places.
 */
object RemoteAccessActions {
    fun sshCommand(context: Context): String =
        context.getString(
            R.string.ssh_connection_info_format,
            ProotRuntime(context).defaultUser,
            AppPreferences.getSshServerPort(context),
        )

    fun copySshCommand(context: Context) {
        copyToClipboard(context, "ssh command", sshCommand(context))
        Toast.makeText(context, R.string.ssh_copied_command_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * SFTP shares SSH's account/password and (aside from listening on its
     * own port -- see [BootstrapService.writeSshdConfig]) is the exact same
     * sshd process, so this is just [sshCommand] with `sftp` in place of
     * `ssh` and the separate SFTP port.
     */
    fun sftpCommand(context: Context): String =
        context.getString(
            R.string.sftp_connection_info_format,
            ProotRuntime(context).defaultUser,
            AppPreferences.getSftpServerPort(context),
        )

    fun copySftpCommand(context: Context) {
        copyToClipboard(context, "sftp command", sftpCommand(context))
        Toast.makeText(context, R.string.sftp_copied_command_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * `ssh-keygen -R` command (run on the *client*, e.g. in Termux) to clear
     * a stale known_hosts entry for this app's SSH endpoint. Needed because
     * the rootfs -- and therefore its `/etc/ssh/ssh_host_*_key` host keys --
     * lives entirely in this app's private storage: reinstalling CoDebian
     * (or clearing its data) wipes the old rootfs and a fresh one generates
     * brand new host keys on next boot, so any client that already
     * connected once will refuse the new connection with a "REMOTE HOST
     * IDENTIFICATION HAS CHANGED" warning until its own known_hosts entry
     * for 127.0.0.1:<port> is removed. Always uses the bracketed
     * `[host]:port` known_hosts notation since the port is essentially
     * never the standard 22.
     */
    fun resetHostKeyCommand(context: Context): String =
        context.getString(R.string.ssh_reset_command_format, AppPreferences.getSshServerPort(context))

    fun copyResetHostKeyCommand(context: Context) {
        copyToClipboard(context, "ssh reset command", resetHostKeyCommand(context))
        Toast.makeText(context, R.string.ssh_copied_reset_command_toast, Toast.LENGTH_SHORT).show()
    }

    fun copySshPassword(context: Context) {
        copyToClipboard(context, "ssh password", AppPreferences.getOrCreateSshServerPassword(context))
        Toast.makeText(context, R.string.ssh_copied_password_toast, Toast.LENGTH_SHORT).show()
    }

    fun openTermux(activity: AppCompatActivity) {
        copyToClipboard(activity, "ssh command", sshCommand(activity))
        Toast.makeText(activity, R.string.ssh_copied_command_toast, Toast.LENGTH_SHORT).show()
        TermuxLauncher.openOrOfferInstall(activity)
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
