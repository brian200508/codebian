package dev.codebian.app

import android.content.Context

/**
 * Centralized typed access to the app's persisted UI preferences
 * (SharedPreferences-backed). Kept separate from MainActivity's existing
 * consent-flag constants so FloatingMenuButton, ExtraKeysBar, and
 * MainActivity all share one single source of truth for these settings
 * instead of duplicating keys/defaults.
 */
enum class KeysBarSize(val labelResId: Int, val heightDp: Int, val textSp: Float, val paddingHDp: Int, val paddingVDp: Int) {
    SMALL_MINUS(R.string.keys_size_small_minus, 32, 10f, 4, 2),
    SMALL(R.string.keys_size_small, 40, 12f, 6, 4),
    MEDIUM(R.string.keys_size_medium, 48, 13f, 8, 6),
    MEDIUM_PLUS(R.string.keys_size_medium_plus, 56, 14f, 10, 8),
    LARGE(R.string.keys_size_large, 64, 16f, 12, 10);

    fun next(): KeysBarSize = entries[(ordinal + 1) % entries.size]
}

/**
 * Independent axis from [KeysBarSize] (which controls button height): scales
 * each key button's rendered width (relative to its natural WRAP_CONTENT
 * width at 1.00) so users can trade off "more keys fit on screen" (narrower)
 * against "easier to tap" (wider), without needing a whole separate row of
 * new height presets for the same effect. 1.00 matches pre-existing
 * behavior exactly, so upgrading users see no visual change until they
 * explicitly pick something else.
 */
enum class KeyWidth(val labelResId: Int, val factor: Float) {
    QUARTER(R.string.key_width_0_25, 0.25f),
    HALF(R.string.key_width_0_50, 0.50f),
    THREE_QUARTER(R.string.key_width_0_75, 0.75f),
    FULL(R.string.key_width_1_00, 1.00f),
}

/**
 * Six-way theme mode: the three base modes (DARK/LIGHT/SYSTEM) control only
 * AppCompatDelegate's day/night resolution, same static @color/key_* palette
 * as before. The three *_WALLPAPER variants additionally apply
 * ThemeOverlay.CoDebian.DynamicWallpaper.{Dark,Light} (values-v31) on top,
 * remapping colorKeyBarBg/colorKeyBg/colorKeyBgActive/colorKeyFg plus the
 * standard Material color roles (colorPrimary/colorSurface/colorOnSurface,
 * so dialogs and popup menus follow along too) onto the system's
 * per-wallpaper Material You palette. On API < 31 they silently fall back to
 * the plain static palette, since the underlying @android:color/system_*
 * resources don't exist before that.
 */
enum class ThemeMode(val labelResId: Int) {
    DARK(R.string.theme_mode_dark),
    LIGHT(R.string.theme_mode_light),
    SYSTEM(R.string.theme_mode_system),
    DARK_WALLPAPER(R.string.theme_mode_dark_wallpaper),
    LIGHT_WALLPAPER(R.string.theme_mode_light_wallpaper),
    SYSTEM_WALLPAPER(R.string.theme_mode_system_wallpaper);

    val isWallpaperStyle: Boolean get() = this == DARK_WALLPAPER || this == LIGHT_WALLPAPER || this == SYSTEM_WALLPAPER
}

object AppPreferences {
    private const val PREFS_NAME = "codebian_prefs"

    private const val KEY_KEYS_BAR_VISIBLE = "keys_bar_visible"
    private const val KEY_FULLSCREEN_ENABLED = "fullscreen_enabled"
    private const val KEY_KEYS_BAR_SIZE = "keys_bar_size"
    private const val KEY_KEYS_BAR_WIDTH = "keys_bar_key_width"
    private const val KEY_FAB_X = "fab_x"
    private const val KEY_FAB_Y = "fab_y"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SSH_SERVER_ENABLED = "ssh_server_enabled"
    private const val KEY_SSH_SERVER_PORT = "ssh_server_port"
    private const val KEY_SFTP_SERVER_PORT = "sftp_server_port"
    private const val KEY_SSH_SERVER_PASSWORD_LEGACY = "ssh_server_password"
    private const val KEY_SSH_SERVER_PASSWORD_ENC = "ssh_server_password_enc"
    private const val KEY_SSH_SERVER_USE_CUSTOM_PASSWORD = "ssh_server_use_custom_password"
    private const val KEY_WAKE_LOCK_ENABLED = "wake_lock_enabled"
    private const val KEY_WORKSPACE_TREE_URI = "workspace_tree_uri"

    // code-server itself (the in-app editor). Off by default (`--auth none`)
    // since the WebView is already the only client that can reach it
    // (loopback-bound); enabling auth adds a password prompt inside the
    // WebView, useful mainly if the port is ever tunneled out to another
    // device (e.g. via `adb forward`/an SSH local port-forward).
    private const val KEY_CODE_SERVER_PORT = "code_server_port"
    private const val KEY_CODE_SERVER_AUTH_ENABLED = "code_server_auth_enabled"
    private const val KEY_CODE_SERVER_PASSWORD_ENC = "code_server_password_enc"
    private const val KEY_CODE_SERVER_USE_CUSTOM_PASSWORD = "code_server_use_custom_password"

    // File Exposure (shared by MCP filesystem server -- see ExposureActions).
    private const val KEY_EXPOSURE_HOME_ENABLED = "exposure_home_enabled"
    private const val KEY_EXPOSURE_WORKSPACE_ENABLED = "exposure_workspace_enabled"
    private const val KEY_EXPOSURE_SHARED_ENABLED = "exposure_shared_enabled"
    private const val KEY_EXPOSURE_INCLUDE_PATTERNS = "exposure_include_patterns"
    private const val KEY_EXPOSURE_EXCLUDE_PATTERNS = "exposure_exclude_patterns"

    private const val KEY_MCP_SERVER_ENABLED = "mcp_server_enabled"
    private const val KEY_MCP_SERVER_PORT = "mcp_server_port"
    private const val KEY_MCP_API_KEY_ENC = "mcp_api_key_enc"
    private const val KEY_MCP_SERVER_USE_CUSTOM_KEY = "mcp_server_use_custom_key"

    const val DEFAULT_SSH_SERVER_PORT = 8022
    const val DEFAULT_SFTP_SERVER_PORT = 8023
    const val DEFAULT_MCP_SERVER_PORT = 3900

    /** Matches code-server's own conventional default port, so "keep the code-server defaults" holds unless overridden. */
    const val DEFAULT_CODE_SERVER_PORT = 8080

    /**
     * In-memory-only (never persisted) override used to live-preview a theme
     * change from SettingsDialog before the user taps Apply. A theme change
     * only visually takes effect after Activity.recreate() (see
     * MainActivity.applyNightModePreference/applyDynamicWallpaperOverlayIfNeeded),
     * which re-reads getEffectiveThemeMode() on the fresh onCreate() -- so
     * without this override, recreate() would just read back the last
     * *persisted* mode and silently discard the unsaved live preview.
     * Cleared whenever the dialog applies, reverts, resets to default, or is
     * cancelled.
     */
    var themeModePreviewOverride: ThemeMode? = null

    /** SettingsDialog sets this before Activity.recreate() so MainActivity's onCreate() knows to reopen it. */
    var reopenSettingsDialogAfterRecreate: Boolean = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isKeysBarVisible(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_KEYS_BAR_VISIBLE, true)

    fun setKeysBarVisible(ctx: Context, visible: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_KEYS_BAR_VISIBLE, visible).apply()
    }

    fun isFullscreenEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FULLSCREEN_ENABLED, false)

    fun setFullscreenEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FULLSCREEN_ENABLED, enabled).apply()
    }

    fun getKeysBarSize(ctx: Context): KeysBarSize {
        val name = prefs(ctx).getString(KEY_KEYS_BAR_SIZE, null) ?: return KeysBarSize.MEDIUM_PLUS
        return try {
            KeysBarSize.valueOf(name)
        } catch (e: IllegalArgumentException) {
            KeysBarSize.MEDIUM_PLUS
        }
    }

    fun setKeysBarSize(ctx: Context, size: KeysBarSize) {
        prefs(ctx).edit().putString(KEY_KEYS_BAR_SIZE, size.name).apply()
    }

    fun getKeysBarKeyWidth(ctx: Context): KeyWidth {
        val name = prefs(ctx).getString(KEY_KEYS_BAR_WIDTH, null) ?: return KeyWidth.FULL
        return try {
            KeyWidth.valueOf(name)
        } catch (e: IllegalArgumentException) {
            KeyWidth.FULL
        }
    }

    fun setKeysBarKeyWidth(ctx: Context, width: KeyWidth) {
        prefs(ctx).edit().putString(KEY_KEYS_BAR_WIDTH, width.name).apply()
    }

    /** Returns null if the floating button has never been dragged yet (use default gravity position). */
    fun getFabPosition(ctx: Context): Pair<Int, Int>? {
        val p = prefs(ctx)
        if (!p.contains(KEY_FAB_X) || !p.contains(KEY_FAB_Y)) return null
        return p.getInt(KEY_FAB_X, 0) to p.getInt(KEY_FAB_Y, 0)
    }

    fun setFabPosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_FAB_X, x).putInt(KEY_FAB_Y, y).apply()
    }

    fun getThemeMode(ctx: Context): ThemeMode {
        val name = prefs(ctx).getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return try {
            ThemeMode.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(ctx: Context, mode: ThemeMode) {
        prefs(ctx).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun getEffectiveThemeMode(ctx: Context): ThemeMode = themeModePreviewOverride ?: getThemeMode(ctx)

    fun isSshServerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SSH_SERVER_ENABLED, false)

    fun setSshServerEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SSH_SERVER_ENABLED, enabled).apply()
    }

    fun getSshServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SSH_SERVER_PORT, DEFAULT_SSH_SERVER_PORT)

    fun setSshServerPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_SSH_SERVER_PORT, port).apply()
    }

    /**
     * SFTP is not a separate server/account -- it's the same sshd process's
     * `Subsystem sftp` (see [BootstrapService.writeSshdConfig]), authenticated
     * with the exact same account/password as plain SSH. This is only a
     * second *port* sshd additionally listens on (sshd_config supports
     * multiple `Port` lines), purely so a dedicated SFTP client can have its
     * own memorable/preconfigured port distinct from an SSH terminal client,
     * without needing a second credential.
     */
    fun getSftpServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SFTP_SERVER_PORT, DEFAULT_SFTP_SERVER_PORT)

    fun setSftpServerPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_SFTP_SERVER_PORT, port).apply()
    }

    /** Whether the currently-active SSH password was explicitly set by the user (vs. auto-generated). */
    fun isUsingCustomSshPassword(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SSH_SERVER_USE_CUSTOM_PASSWORD, false)

    /** Whether the app should hold a partial wake lock while its bootstrap/server foreground service is running, keeping the CPU awake (screen may still turn off) so code-server/sshd keep responding while the screen is locked. */
    fun isWakeLockEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_WAKE_LOCK_ENABLED, false)

    fun setWakeLockEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WAKE_LOCK_ENABLED, enabled).apply()
    }

    /** Persisted SAF tree Uri (string form) of the folder linked as the rootfs `~/workspace` mirror, or null if none has been picked yet. */
    fun getWorkspaceTreeUri(ctx: Context): String? =
        prefs(ctx).getString(KEY_WORKSPACE_TREE_URI, null)

    fun setWorkspaceTreeUri(ctx: Context, uriString: String?) {
        prefs(ctx).edit().putString(KEY_WORKSPACE_TREE_URI, uriString).apply()
    }

    /**
     * Lazily generates (once) and persists a random password for the
     * in-rootfs "coder" user's SSH login -- loopback-only by default, so this
     * is a local convenience credential rather than an internet-facing one,
     * but still randomly generated per-install rather than hardcoded. Stored
     * encrypted via [SecureStorage] (Keystore-backed AES-256-GCM). If a
     * custom password was set instead (see [setCustomSshPassword]), that is
     * returned here instead of an auto-generated one.
     */
    fun getOrCreateSshServerPassword(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_SSH_SERVER_PASSWORD_ENC, null)?.let { enc ->
            SecureStorage.decrypt(enc)?.let { return it }
        }
        // Legacy migration: installs from before encryption was added stored
        // the password in plaintext under KEY_SSH_SERVER_PASSWORD_LEGACY.
        // Re-encrypt it in place (preserving the value) so already-configured
        // SSH clients keep working, instead of silently rotating it.
        p.getString(KEY_SSH_SERVER_PASSWORD_LEGACY, null)?.let { legacy ->
            p.edit()
                .putString(KEY_SSH_SERVER_PASSWORD_ENC, SecureStorage.encrypt(legacy))
                .remove(KEY_SSH_SERVER_PASSWORD_LEGACY)
                .apply()
            return legacy
        }
        return regenerateRandomSshPassword(ctx)
    }

    /** Sets an explicit, user-chosen SSH password (encrypted at rest). Blank clears back to auto-generate. */
    fun setCustomSshPassword(ctx: Context, password: String): String {
        if (password.isBlank()) return regenerateRandomSshPassword(ctx)
        prefs(ctx).edit()
            .putString(KEY_SSH_SERVER_PASSWORD_ENC, SecureStorage.encrypt(password))
            .putBoolean(KEY_SSH_SERVER_USE_CUSTOM_PASSWORD, true)
            .apply()
        return password
    }

    /** Generates a fresh random password, persists it (encrypted), and switches out of custom-password mode. */
    fun regenerateRandomSshPassword(ctx: Context): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val generated = (1..16).map { chars.random() }.joinToString("")
        prefs(ctx).edit()
            .putString(KEY_SSH_SERVER_PASSWORD_ENC, SecureStorage.encrypt(generated))
            .putBoolean(KEY_SSH_SERVER_USE_CUSTOM_PASSWORD, false)
            .apply()
        return generated
    }

    // ---------------------------------------------------------------------
    // File Exposure (shared by the MCP filesystem server, see
    // ExposureActions.kt). "Home directory" is on by default so upgrading
    // users who enable MCP get the same behavior as SSH already has
    // (whole ~/ visible) without an extra step; the other two sources are
    // opt-in additions on top of it.
    // ---------------------------------------------------------------------

    fun isExposureHomeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EXPOSURE_HOME_ENABLED, true)

    fun setExposureHomeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EXPOSURE_HOME_ENABLED, enabled).apply()
    }

    /** Whether the SAF-linked `~/workspace` mirror (see [SafActions]) should also be visible to MCP. */
    fun isExposureWorkspaceEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EXPOSURE_WORKSPACE_ENABLED, false)

    fun setExposureWorkspaceEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EXPOSURE_WORKSPACE_ENABLED, enabled).apply()
    }

    /** Whether the app's own external-files "shared" folder (live proot-bound, no SAF/permission needed) should be visible. */
    fun isExposureSharedEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EXPOSURE_SHARED_ENABLED, false)

    fun setExposureSharedEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_EXPOSURE_SHARED_ENABLED, enabled).apply()
    }

    /** Newline-separated gitignore-style globs; blank means "include everything" from the enabled sources. */
    fun getExposureIncludePatterns(ctx: Context): String =
        prefs(ctx).getString(KEY_EXPOSURE_INCLUDE_PATTERNS, "") ?: ""

    fun setExposureIncludePatterns(ctx: Context, patterns: String) {
        prefs(ctx).edit().putString(KEY_EXPOSURE_INCLUDE_PATTERNS, patterns).apply()
    }

    /** Newline-separated gitignore-style globs, applied after include-patterns. */
    fun getExposureExcludePatterns(ctx: Context): String =
        prefs(ctx).getString(KEY_EXPOSURE_EXCLUDE_PATTERNS, "") ?: ""

    fun setExposureExcludePatterns(ctx: Context, patterns: String) {
        prefs(ctx).edit().putString(KEY_EXPOSURE_EXCLUDE_PATTERNS, patterns).apply()
    }

    // ---------------------------------------------------------------------
    // MCP filesystem server (bridged stdio -> HTTP/SSE via mcp-proxy).
    // Loopback-only; auth is an API key (X-API-Key header), not a
    // username/password pair -- mcp-proxy has no user concept.
    // ---------------------------------------------------------------------

    fun isMcpServerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MCP_SERVER_ENABLED, false)

    fun setMcpServerEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MCP_SERVER_ENABLED, enabled).apply()
    }

    fun getMcpServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MCP_SERVER_PORT, DEFAULT_MCP_SERVER_PORT)

    fun setMcpServerPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_MCP_SERVER_PORT, port).apply()
    }

    fun isUsingCustomMcpApiKey(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MCP_SERVER_USE_CUSTOM_KEY, false)

    fun getOrCreateMcpApiKey(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_MCP_API_KEY_ENC, null)?.let { enc ->
            SecureStorage.decrypt(enc)?.let { return it }
        }
        return regenerateRandomMcpApiKey(ctx)
    }

    fun setCustomMcpApiKey(ctx: Context, key: String): String {
        if (key.isBlank()) return regenerateRandomMcpApiKey(ctx)
        prefs(ctx).edit()
            .putString(KEY_MCP_API_KEY_ENC, SecureStorage.encrypt(key))
            .putBoolean(KEY_MCP_SERVER_USE_CUSTOM_KEY, true)
            .apply()
        return key
    }

    fun regenerateRandomMcpApiKey(ctx: Context): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val generated = (1..32).map { chars.random() }.joinToString("")
        prefs(ctx).edit()
            .putString(KEY_MCP_API_KEY_ENC, SecureStorage.encrypt(generated))
            .putBoolean(KEY_MCP_SERVER_USE_CUSTOM_KEY, false)
            .apply()
        return generated
    }

    // ---------------------------------------------------------------------
    // code-server itself. Loopback-only, `--auth none` by default (see
    // KEY_CODE_SERVER_AUTH_ENABLED doc above); port defaults to
    // code-server's own conventional 8080.
    // ---------------------------------------------------------------------

    fun getCodeServerPort(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CODE_SERVER_PORT, DEFAULT_CODE_SERVER_PORT)

    fun setCodeServerPort(ctx: Context, port: Int) {
        prefs(ctx).edit().putInt(KEY_CODE_SERVER_PORT, port).apply()
    }

    fun isCodeServerAuthEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CODE_SERVER_AUTH_ENABLED, false)

    fun setCodeServerAuthEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CODE_SERVER_AUTH_ENABLED, enabled).apply()
    }

    fun isUsingCustomCodeServerPassword(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CODE_SERVER_USE_CUSTOM_PASSWORD, false)

    /** Same lazily-generated-and-encrypted pattern as [getOrCreateSshServerPassword], only used while [isCodeServerAuthEnabled] is on. */
    fun getOrCreateCodeServerPassword(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_CODE_SERVER_PASSWORD_ENC, null)?.let { enc ->
            SecureStorage.decrypt(enc)?.let { return it }
        }
        return regenerateRandomCodeServerPassword(ctx)
    }

    fun setCustomCodeServerPassword(ctx: Context, password: String): String {
        if (password.isBlank()) return regenerateRandomCodeServerPassword(ctx)
        prefs(ctx).edit()
            .putString(KEY_CODE_SERVER_PASSWORD_ENC, SecureStorage.encrypt(password))
            .putBoolean(KEY_CODE_SERVER_USE_CUSTOM_PASSWORD, true)
            .apply()
        return password
    }

    fun regenerateRandomCodeServerPassword(ctx: Context): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val generated = (1..16).map { chars.random() }.joinToString("")
        prefs(ctx).edit()
            .putString(KEY_CODE_SERVER_PASSWORD_ENC, SecureStorage.encrypt(generated))
            .putBoolean(KEY_CODE_SERVER_USE_CUSTOM_PASSWORD, false)
            .apply()
        return generated
    }
}
