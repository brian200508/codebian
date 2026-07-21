package dev.codebian.app

import android.app.Dialog
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton

/**
 * Gives the app's main WebView real support for `window.open()` popups, most
 * importantly the GitHub OAuth flow used by VS Code's built-in GitHub
 * Authentication extension (and by extension: Settings Sync, Pull Requests,
 * etc.) when running under code-server in the browser.
 *
 * Root cause this fixes: MainActivity's WebView previously had no
 * WebChromeClient at all, so `window.open()` had nowhere to go. The default
 * single-window WebView behavior is to navigate the *same* page to the new
 * URL instead of opening a real second window -- so the user got stuck
 * looking at GitHub's "you may close this window" page with no way back
 * except force-stopping the whole app (window.close() has no effect because
 * there was never a real second window for Android to close), and the
 * opener page's postMessage/redirect handshake with the popup never
 * completed, so the extension never got to persist a valid session token.
 *
 * Fix: implement onCreateWindow to host a second, throwaway WebView inside a
 * dialog (with its own visible close button as a manual safety net, in case
 * a given OAuth flow's final page never calls window.close() itself), and
 * onCloseWindow to dismiss it when the flow finishes normally.
 */
class AuthPopupManager(private val hostWebView: CodebianWebView) {

    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null

    fun createWindowFor(@Suppress("UNUSED_PARAMETER") isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // Only user-initiated navigations should be allowed to spawn a popup
        // (blocks unsolicited window.open() spam from arbitrary pages).
        if (!isUserGesture) return false

        closePopup()

        val context = hostWebView.context
        val newWebView = WebView(context)
        newWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)
        newWebView.webViewClient = WebViewClient()
        newWebView.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                closePopup()
            }
        }

        val closeButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = context.getString(R.string.auth_popup_close_content_description)
        }
        val container = FrameLayout(context).apply {
            addView(
                newWebView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(
                closeButton,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END },
            )
        }

        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.setContentView(container)
        dialog.setOnDismissListener {
            newWebView.stopLoading()
            newWebView.destroy()
            if (popupWebView === newWebView) {
                popupWebView = null
                popupDialog = null
            }
        }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()

        popupDialog = dialog
        popupWebView = newWebView

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }

    fun closePopup() {
        popupDialog?.dismiss()
        popupDialog = null
        popupWebView = null
    }
}
