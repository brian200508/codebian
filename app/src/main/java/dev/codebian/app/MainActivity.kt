package dev.codebian.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.codebian.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var extraKeysBar: ExtraKeysBar
    private lateinit var floatingMenuButton: FloatingMenuButton
    private lateinit var safActions: SafActions
    private lateinit var configBackupActions: ConfigBackupActions
    private lateinit var authPopupManager: AuthPopupManager
    private var codeServerLoaded = false
    private var lastLoadedPort: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightModePreference()
        super.onCreate(savedInstanceState)
        applyDynamicWallpaperOverlayIfNeeded()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupExtraKeysBarInsets()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }

        setupWebView()
        extraKeysBar = ExtraKeysBar(binding.extraKeysBar, binding.webView)
        extraKeysBar.build()
        safActions = SafActions(this)
        configBackupActions = ConfigBackupActions(this)
        floatingMenuButton = FloatingMenuButton(
            activity = this,
            root = binding.root,
            button = binding.floatingMenuButton,
            keyBarScroll = binding.keyBarScroll,
            extraKeysBar = extraKeysBar,
            safActions = safActions,
            configBackupActions = configBackupActions,
        )
        floatingMenuButton.setup()

        if (AppPreferences.reopenSettingsDialogAfterRecreate) {
            AppPreferences.reopenSettingsDialogAfterRecreate = false
            floatingMenuButton.showSettingsDialog()
        }

        binding.retryConsentButton.setOnClickListener {
            binding.retryConsentButton.visibility = android.view.View.GONE
            showConsentDialog()
        }

        if (hasConsent()) {
            startBootstrap()
        } else {
            showConsentDialog()
        }
    }

    /**
     * Must run before super.onCreate() -- AppCompatDelegate's night-mode
     * resolution needs to be set before the Activity's resources/theme are
     * first resolved during super.onCreate(), otherwise the initial layout
     * inflation would use the previous mode and require an extra recreate()
     * to correct itself.
     */
    private fun applyNightModePreference() {
        val mode = AppPreferences.getEffectiveThemeMode(this)
        val nightMode = when (mode) {
            ThemeMode.DARK, ThemeMode.DARK_WALLPAPER -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT, ThemeMode.LIGHT_WALLPAPER -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.SYSTEM, ThemeMode.SYSTEM_WALLPAPER -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /**
     * Applied right after super.onCreate() (so night mode above has already
     * resolved into `resources.configuration`) but before inflating the
     * layout, so every view --including the key bar and any dialogs/popup
     * menus created later-- picks up the remapped colorKeyBarBg/colorKeyBg/
     * colorKeyBgActive/colorKeyFg plus colorPrimary/colorSurface/
     * colorOnSurface from this overlay. No-op below API 31 or for the three
     * non-wallpaper modes (see ThemeMode.isWallpaperStyle KDoc).
     */
    private fun applyDynamicWallpaperOverlayIfNeeded() {
        val mode = AppPreferences.getEffectiveThemeMode(this)
        if (!mode.isWallpaperStyle || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val dark = when (mode) {
            ThemeMode.DARK_WALLPAPER -> true
            ThemeMode.LIGHT_WALLPAPER -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val overlayResId = if (dark) {
            R.style.ThemeOverlay_CoDebian_DynamicWallpaper_Dark
        } else {
            R.style.ThemeOverlay_CoDebian_DynamicWallpaper_Light
        }
        theme.applyStyle(overlayResId, true)
    }

    /**
     * Play Store policy compliance: CoDebian must not silently download and
     * execute a Linux userspace + native binaries on first launch without
     * the user's explicit, informed action. This dialog is that action; the
     * bootstrap service is only ever started after the user taps "accept"
     * here (or on a prior launch), never automatically on app start.
     */
    private fun showConsentDialog() {
        AlertDialog.Builder(this, R.style.ThemeOverlay_CoDebian_AlertDialog)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_accept) { _, _ ->
                markConsentGiven()
                startBootstrap()
            }
            .setNegativeButton(R.string.consent_decline) { _, _ ->
                binding.statusText.text = getString(R.string.consent_declined_message)
                binding.retryConsentButton.visibility = android.view.View.VISIBLE
            }
            .show()
    }

    private fun hasConsent(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_CONSENT_GIVEN, false)

    private fun markConsentGiven() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_CONSENT_GIVEN, true)
            .apply()
    }

    private fun startBootstrap() {
        binding.retryConsentButton.visibility = android.view.View.GONE
        ActivityCompat.startForegroundService(this, Intent(this, BootstrapService::class.java))
        observeBootstrapState()
    }

    /**
     * On Android 15+ (targetSdk 35), edge-to-edge is enforced by default and
     * windowSoftInputMode="adjustResize" no longer reliably shrinks the
     * window when the IME appears -- the system instead draws the keyboard
     * as an overlay on top of the existing layout, which was silently
     * hiding our extra-keys bar behind the keyboard on-device (verified on
     * the Galaxy S25+ / One UI). We fix this ourselves: enable edge-to-edge
     * dispatch and apply the IME's bottom inset as padding on the key-bar's
     * scroll container, so it always floats immediately above the visible
     * keyboard (and lies flat at the bottom of the screen when the keyboard
     * is hidden).
     */
    private fun setupExtraKeysBarInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, statusTop, view.paddingRight, view.paddingBottom)
            if (::floatingMenuButton.isInitialized) {
                floatingMenuButton.updateNavBottomInset(
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                )
            }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.keyBarScroll) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, maxOf(imeBottom, navBottom))
            insets
        }
    }

    private fun setupWebView() {
        // Debug-only: lets us attach Chrome DevTools (via `adb forward` to the
        // webview_devtools_remote socket) for diagnosing JS/keybinding issues
        // directly, since this WebView is otherwise fully app-owned/isolated.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Required for onCreateWindow() to actually be invoked for
            // window.open() -- without these two, WebView either ignores
            // window.open() or navigates the same page away instead of
            // creating a separate window. This is what the GitHub OAuth
            // flow (and any other extension using a popup-based auth
            // handshake) relies on.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        binding.webView.webViewClient = WebViewClient()

        // Cookies must survive across app restarts for GitHub/session auth
        // to persist, and third-party cookies must be explicitly allowed
        // since the OAuth popup navigates to github.com while the opener
        // page is served from 127.0.0.1.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        authPopupManager = AuthPopupManager(binding.webView)
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean = authPopupManager.createWindowFor(isDialog, isUserGesture, resultMsg)
        }
    }

    private fun observeBootstrapState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BootstrapManager.state.collect { state ->
                    when (state) {
                        is BootstrapState.Downloading -> {
                            binding.statusText.text = "Downloading ${state.what}\u2026 ${state.percent}%"
                            renderBootstrapSteps(STEP_CONTAINER)
                        }
                        BootstrapState.VerifyingDownload -> {
                            binding.statusText.text = "Verifying download\u2026"
                            renderBootstrapSteps(STEP_CONTAINER)
                        }
                        BootstrapState.ExtractingRootfs -> {
                            binding.statusText.text = "Extracting Debian rootfs\u2026"
                            renderBootstrapSteps(STEP_CONTAINER)
                        }
                        BootstrapState.InstallingCodeServer -> {
                            binding.statusText.text = "Installing code-server\u2026"
                            renderBootstrapSteps(STEP_CODE_SERVER)
                        }
                        is BootstrapState.InstallingBundledTools -> {
                            val label = when (state.tool) {
                                BootstrapTool.GIT -> "Git"
                                BootstrapTool.NODEJS -> "Node.js LTS"
                                BootstrapTool.PYTHON -> "Python 3"
                                else -> state.tool
                            }
                            binding.statusText.text = "Installing $label\u2026"
                            renderBootstrapSteps(state.tool)
                        }
                        BootstrapState.StartingServer -> {
                            binding.statusText.text = "Starting code-server\u2026"
                            renderBootstrapSteps(STEP_STARTING)
                        }
                        BootstrapState.UpdatingCodeServer -> {
                            binding.statusOverlay.visibility = android.view.View.VISIBLE
                            binding.statusText.text = "Updating code-server\u2026"
                            binding.bootstrapStepsText.visibility = android.view.View.GONE
                        }
                        BootstrapState.RestartingCodeServer -> {
                            binding.statusOverlay.visibility = android.view.View.VISIBLE
                            binding.statusText.text = "Applying code-server settings\u2026"
                            binding.bootstrapStepsText.visibility = android.view.View.GONE
                        }
                        is BootstrapState.Ready -> {
                            val wasUpdating = binding.statusOverlay.visibility == android.view.View.VISIBLE
                            binding.statusOverlay.visibility = android.view.View.GONE
                            binding.bootstrapStepsText.visibility = android.view.View.GONE
                            if (!codeServerLoaded) {
                                codeServerLoaded = true
                                lastLoadedPort = state.port
                                binding.webView.loadUrl("http://127.0.0.1:${state.port}/")
                            } else if (state.port != lastLoadedPort) {
                                // The code-server port itself changed (Settings
                                // dialog) -- a plain reload() would just retry
                                // the OLD port, which no longer has anything
                                // listening on it, so load the new URL outright.
                                lastLoadedPort = state.port
                                binding.webView.loadUrl("http://127.0.0.1:${state.port}/")
                            } else if (wasUpdating) {
                                // The service just restarted code-server after an
                                // in-app update -- reload so the WebView picks up
                                // the freshly-installed client instead of showing
                                // a stale connection error from the brief restart.
                                binding.webView.reload()
                            }
                        }
                        is BootstrapState.Error ->
                            binding.statusText.text = "Error: ${state.message}"
                        BootstrapState.Idle -> Unit
                    }
                }
            }
        }
    }

    /**
     * Renders a simple checklist of the main first-run bootstrap steps
     * (container / code-server / git / Node.js LTS / Python) below the
     * status text, so a slow step (e.g. a big apt-get download) doesn't
     * look stuck -- the user can see exactly which step is active and
     * which are already done. [currentStep] is one of the STEP_* /
     * [BootstrapTool] constants; passing [STEP_STARTING] marks every step
     * as complete (the checklist's job is done once code-server itself is
     * being launched).
     */
    private fun renderBootstrapSteps(currentStep: String) {
        val steps = listOf(
            STEP_CONTAINER to "Debian container",
            STEP_CODE_SERVER to "code-server",
            BootstrapTool.GIT to "Git",
            BootstrapTool.NODEJS to "Node.js LTS",
            BootstrapTool.PYTHON to "Python 3",
        )
        val currentIndex = if (currentStep == STEP_STARTING) {
            steps.size
        } else {
            steps.indexOfFirst { it.first == currentStep }
        }
        binding.bootstrapStepsText.text = steps.mapIndexed { i, (_, label) ->
            val marker = when {
                currentIndex == -1 -> "\u2022" // unknown step -- shouldn't happen, but don't crash the checklist over it
                i < currentIndex -> "\u2713"
                i == currentIndex -> "\u25B6"
                else -> "\u25CB"
            }
            "$marker $label"
        }.joinToString("\n")
        binding.bootstrapStepsText.visibility = android.view.View.VISIBLE
    }

    /**
     * Modern WebView's CookieManager already persists cookies to disk
     * automatically, but an explicit flush() here removes any chance of a
     * lost GitHub session cookie if the process is killed shortly after
     * completing the OAuth flow (e.g. user backgrounds the app right away).
     */
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        authPopupManager.closePopup()
        super.onDestroy()
    }

    /**
     * Physical-keyboard path. Android's own focus system will otherwise
     * consume Escape (back navigation) and Tab (view focus change) before
     * they ever reach the WebView, and Ctrl/Alt combos aren't guaranteed to
     * reach page JS either -- so we forward the keys code-server actually
     * needs ourselves and let everything else fall through to the normal
     * WebView input path (plain typing already works without help).
     *
     * Also honors the extra-keys row's sticky CTRL/ALT toggles for plain
     * letter/digit KeyEvents: some soft-keyboard/IME configurations (and
     * key-injection tools) deliver a real KeyEvent for a letter instead of
     * going through InputConnection.commitText, which CodebianWebView
     * intercepts separately -- so both paths need to check the sticky
     * modifier state to reliably support "hold CTRL, tap a letter" from the
     * on-screen keyboard.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyBridge.shouldInterceptHardwareKey(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                KeyBridge.forwardHardwareKeyEvent(binding.webView, event)
            }
            return true
        }
        val webView = binding.webView
        val stickyModifierHeld = webView.ctrlHeld || webView.altHeld
        if (stickyModifierHeld && event.action == KeyEvent.ACTION_DOWN && KeyBridge.isPrintableKey(event.keyCode)) {
            KeyBridge.forwardWithStickyModifiers(
                webView, event,
                ctrl = webView.ctrlHeld, alt = webView.altHeld, shift = webView.shiftHeld,
            )
            webView.ctrlHeld = false
            webView.altHeld = false
            webView.shiftHeld = false
            webView.onModifierConsumed?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val PREFS_NAME = "codebian_prefs"
        private const val PREF_CONSENT_GIVEN = "consent_given"
        private const val STEP_CONTAINER = "container"
        private const val STEP_CODE_SERVER = "code_server"
        private const val STEP_STARTING = "starting"
    }
}
