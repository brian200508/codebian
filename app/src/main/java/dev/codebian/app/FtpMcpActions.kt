package dev.codebian.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Copy-command/copy-credential actions for the MCP filesystem server and
 * code-server sections of the Settings dialog -- same shape as
 * [RemoteAccessActions], kept separate since MCP/code-server auth are
 * optional (off by default) features layered on top of the always-present
 * SSH one.
 */
object FtpMcpActions {
    /** URL only -- deliberately excludes the API key (see [copyMcpApiKey] for that), so it's safe to display on-screen (e.g. over someone's shoulder) without exposing the secret. */
    fun mcpUrl(context: Context): String =
        context.getString(R.string.mcp_connection_info_format, AppPreferences.getMcpServerPort(context))

    fun copyMcpUrl(context: Context) {
        copyToClipboard(context, "mcp url", "http://127.0.0.1:${AppPreferences.getMcpServerPort(context)}/sse")
        Toast.makeText(context, R.string.mcp_copied_url_toast, Toast.LENGTH_SHORT).show()
    }

    fun copyMcpApiKey(context: Context) {
        copyToClipboard(context, "mcp api key", AppPreferences.getOrCreateMcpApiKey(context))
        Toast.makeText(context, R.string.mcp_copied_key_toast, Toast.LENGTH_SHORT).show()
    }

    /** Only useful outside the app's own WebView (e.g. a tunneled/forwarded port) -- see [BootstrapService.syncCodeServerState]. */
    fun codeServerUrl(context: Context): String =
        context.getString(R.string.code_server_connection_info_format, AppPreferences.getCodeServerPort(context))

    fun copyCodeServerUrl(context: Context) {
        copyToClipboard(context, "code-server url", codeServerUrl(context))
        Toast.makeText(context, R.string.code_server_copied_url_toast, Toast.LENGTH_SHORT).show()
    }

    fun copyCodeServerPassword(context: Context) {
        copyToClipboard(context, "code-server password", AppPreferences.getOrCreateCodeServerPassword(context))
        Toast.makeText(context, R.string.code_server_copied_password_toast, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
