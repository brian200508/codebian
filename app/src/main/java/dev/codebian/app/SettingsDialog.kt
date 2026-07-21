package dev.codebian.app

import android.app.Dialog
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import dev.codebian.app.databinding.DialogSettingsBinding

/**
 * Consolidated settings dialog, opened by tapping the floating menu button
 * (replacing the previous flat PopupMenu). Currently covers the Display and
 * Keys Bar sections -- Remote Access (SSH/SFTP), Storage (SAF), and
 * Environment (bundled tools/regular-user-sudo/code-server updates) sections
 * will be added here once their backing features exist, per plan.
 *
 * Behavior model (as agreed with the user):
 *  - Opening the dialog snapshots the currently *persisted* settings.
 *  - Every change inside the dialog is applied live immediately (you see
 *    the effect right away: keys bar resizes, fullscreen toggles, theme
 *    switches, etc.) but is NOT written to SharedPreferences yet.
 *  - Save persists the current live values, updates the "last saved"
 *    snapshot, and closes the dialog.
 *  - Revert re-applies the last-saved snapshot live (undoing anything
 *    changed since opening/since the last Save).
 *  - Default applies sensible built-in defaults live (still requires Save
 *    to persist).
 *  - Cancel reverts live state back to last-saved and closes the dialog, so
 *    no unsaved preview lingers afterward.
 *
 * Theme mode is the one setting that requires a full Activity.recreate() to
 * visually take effect (AppCompatDelegate night mode + wallpaper overlay are
 * both resolved in onCreate()). To live-preview it without persisting, we
 * stash the pending value in AppPreferences.themeModePreviewOverride (an
 * in-memory-only field that getEffectiveThemeMode() prefers over the
 * persisted value) and set reopenSettingsDialogAfterRecreate so
 * MainActivity's onCreate() reopens this same dialog right after recreating,
 * preserving the illusion of a single continuous dialog session.
 */
class SettingsDialog(
    private val activity: AppCompatActivity,
    private val extraKeysBar: ExtraKeysBar,
    private val configBackupActions: ConfigBackupActions,
    private val applyFullscreen: () -> Unit,
    private val applyKeysBarVisibility: () -> Unit,
) {
    private data class Snapshot(
        val themeMode: ThemeMode,
        val fullscreen: Boolean,
        val keysBarVisible: Boolean,
        val height: KeysBarSize,
        val keyWidth: KeyWidth,
        val sshEnabled: Boolean,
        val sshPort: Int,
        val sftpPort: Int,
        val exposureHome: Boolean,
        val exposureWorkspace: Boolean,
        val exposureShared: Boolean,
        val exposureInclude: String,
        val exposureExclude: String,
        val mcpEnabled: Boolean,
        val mcpPort: Int,
        val codeServerPort: Int,
        val codeServerAuthEnabled: Boolean,
    )

    private lateinit var binding: DialogSettingsBinding
    private lateinit var dialog: Dialog
    private lateinit var lastSaved: Snapshot

    fun show() {
        binding = DialogSettingsBinding.inflate(LayoutInflater.from(activity))
        lastSaved = readPersisted()

        setupThemeSpinner()
        setupHeightSpinner()
        setupKeyWidthSpinner()
        applyLive(
            // If we're reopening right after a theme-preview recreate(),
            // reflect the in-memory pending value rather than the
            // (still-unsaved) persisted one, so the dialog looks unchanged
            // from the user's perspective across the recreate.
            lastSaved.copy(themeMode = AppPreferences.getEffectiveThemeMode(activity)),
        )

        binding.fullscreenSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setFullscreenEnabled(activity, checked)
            applyFullscreen()
        }
        binding.showKeysBarSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setKeysBarVisible(activity, checked)
            applyKeysBarVisibility()
        }
        binding.sshServerSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setSshServerEnabled(activity, checked)
            BootstrapService.requestSshSync(activity)
            updateSshConnectionInfo()
        }
        binding.sshPortEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySshPortFromField()
        }
        binding.sftpPortEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySftpPortFromField()
        }
        binding.copySshCommandButton.setOnClickListener {
            RemoteAccessActions.copySshCommand(activity)
        }
        binding.copySftpCommandButton.setOnClickListener {
            RemoteAccessActions.copySftpCommand(activity)
        }
        binding.copySshResetCommandButton.setOnClickListener {
            RemoteAccessActions.copyResetHostKeyCommand(activity)
        }
        binding.copySshPasswordButton.setOnClickListener {
            RemoteAccessActions.copySshPassword(activity)
        }
        binding.openTermuxButton.setOnClickListener {
            RemoteAccessActions.openTermux(activity)
        }
        binding.remoteAccessTabLayout.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    binding.sshTabContent.visibility = if (tab.position == 0) android.view.View.VISIBLE else android.view.View.GONE
                    binding.mcpTabContent.visibility = if (tab.position == 1) android.view.View.VISIBLE else android.view.View.GONE
                    binding.codeServerTabContent.visibility = if (tab.position == 2) android.view.View.VISIBLE else android.view.View.GONE
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            },
        )
        binding.exposureHomeCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureHomeEnabled(activity, checked)
        }
        binding.exposureWorkspaceCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureWorkspaceEnabled(activity, checked)
        }
        binding.exposureSharedCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureSharedEnabled(activity, checked)
        }
        binding.exposureIncludeEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) AppPreferences.setExposureIncludePatterns(activity, binding.exposureIncludeEditText.text.toString())
        }
        binding.exposureExcludeEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) AppPreferences.setExposureExcludePatterns(activity, binding.exposureExcludeEditText.text.toString())
        }
        binding.refreshExposureButton.setOnClickListener {
            AppPreferences.setExposureIncludePatterns(activity, binding.exposureIncludeEditText.text.toString())
            AppPreferences.setExposureExcludePatterns(activity, binding.exposureExcludeEditText.text.toString())
            if (ExposureActions.hasAnyExposure(activity)) {
                BootstrapService.requestExposureRefresh(activity)
                android.widget.Toast.makeText(activity, R.string.exposure_refreshed_toast, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(activity, R.string.exposure_none_enabled_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.mcpServerSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setMcpServerEnabled(activity, checked)
            BootstrapService.requestMcpSync(activity)
            updateMcpConnectionInfo()
        }
        binding.mcpPortEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyMcpPortFromField()
        }
        binding.copyMcpUrlButton.setOnClickListener {
            FtpMcpActions.copyMcpUrl(activity)
        }
        binding.copyMcpApiKeyButton.setOnClickListener {
            FtpMcpActions.copyMcpApiKey(activity)
        }
        refreshMcpApiKeyField()
        binding.mcpCustomApiKeyEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyMcpApiKeyFromField()
        }
        binding.generateMcpApiKeyButton.setOnClickListener {
            confirmGenerate(R.string.generate_mcp_key_confirm_message) {
                val generated = AppPreferences.regenerateRandomMcpApiKey(activity)
                binding.mcpCustomApiKeyEditText.setText(generated)
                BootstrapService.requestMcpSync(activity)
                android.widget.Toast.makeText(activity, R.string.mcp_key_generated_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.exportConfigButton.setOnClickListener {
            configBackupActions.exportConfig()
        }
        binding.importConfigButton.setOnClickListener {
            configBackupActions.importConfig()
        }
        configBackupActions.onImported = {
            // A successful import writes straight to SharedPreferences (see
            // ConfigBackupActions.applyImportJson), bypassing this dialog's
            // own live-preview state -- re-read persisted values and
            // re-apply them live so the open dialog reflects the imported
            // config immediately instead of looking stale until reopened.
            lastSaved = readPersisted()
            applyLive(lastSaved.copy(themeMode = AppPreferences.getEffectiveThemeMode(activity)))
        }
        refreshSshPasswordField()
        binding.sshCustomPasswordEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySshPasswordFromField()
        }
        binding.generateSshPasswordButton.setOnClickListener {
            confirmGenerate(R.string.generate_ssh_password_confirm_message) {
                val generated = AppPreferences.regenerateRandomSshPassword(activity)
                binding.sshCustomPasswordEditText.setText(generated)
                BootstrapService.requestSshSync(activity)
                android.widget.Toast.makeText(activity, R.string.ssh_password_generated_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.codeServerAuthSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setCodeServerAuthEnabled(activity, checked)
            BootstrapService.requestCodeServerSync(activity)
            updateCodeServerConnectionInfo()
        }
        binding.codeServerPortEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCodeServerPortFromField()
        }
        binding.copyCodeServerUrlButton.setOnClickListener {
            FtpMcpActions.copyCodeServerUrl(activity)
        }
        binding.copyCodeServerPasswordButton.setOnClickListener {
            FtpMcpActions.copyCodeServerPassword(activity)
        }
        refreshCodeServerPasswordField()
        binding.codeServerCustomPasswordEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyCodeServerPasswordFromField()
        }
        binding.generateCodeServerPasswordButton.setOnClickListener {
            confirmGenerate(R.string.generate_code_server_password_confirm_message) {
                val generated = AppPreferences.regenerateRandomCodeServerPassword(activity)
                binding.codeServerCustomPasswordEditText.setText(generated)
                BootstrapService.requestCodeServerSync(activity)
                android.widget.Toast.makeText(activity, R.string.code_server_password_generated_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        binding.settingsDefaultButton.setOnClickListener { applyLive(defaults()) }
        binding.settingsRevertButton.setOnClickListener { applyLive(lastSaved) }
        binding.settingsCancelButton.setOnClickListener {
            applyLive(lastSaved)
            dialog.dismiss()
        }
        binding.settingsApplyButton.setOnClickListener {
            persistCurrentLiveState()
            lastSaved = readPersisted()
            dialog.dismiss()
        }

        dialog = Dialog(activity).apply {
            setTitle(R.string.settings_dialog_title)
            setContentView(binding.root)
            setOnDismissListener {
                // Cancelling the dialog (back button / outside tap) must
                // revert the same way the Cancel button does, otherwise an
                // unsaved live preview would linger after closing.
                AppPreferences.themeModePreviewOverride = null
                configBackupActions.onImported = null
            }
        }
        dialog.show()
        // A plain Dialog's window defaults to WRAP_CONTENT, sized only as
        // wide as its widest child demands (down to the theme's
        // windowMinWidthMajor/Minor floor, ~65-95% of screen width). Now
        // that the action-button rows use match_parent/weight instead of
        // wrap_content buttons, nothing inside forces that measurement
        // wide anymore, so the whole dialog visibly narrows and every
        // label (Height, Key width, Copy command, ...) starts wrapping.
        // Forcing MATCH_PARENT here (with the standard side margins the
        // dialog already had) keeps the full-width layout regardless of
        // font scale or button content width.
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun readPersisted() = Snapshot(
        themeMode = AppPreferences.getThemeMode(activity),
        fullscreen = AppPreferences.isFullscreenEnabled(activity),
        keysBarVisible = AppPreferences.isKeysBarVisible(activity),
        height = AppPreferences.getKeysBarSize(activity),
        keyWidth = AppPreferences.getKeysBarKeyWidth(activity),
        sshEnabled = AppPreferences.isSshServerEnabled(activity),
        sshPort = AppPreferences.getSshServerPort(activity),
        sftpPort = AppPreferences.getSftpServerPort(activity),
        exposureHome = AppPreferences.isExposureHomeEnabled(activity),
        exposureWorkspace = AppPreferences.isExposureWorkspaceEnabled(activity),
        exposureShared = AppPreferences.isExposureSharedEnabled(activity),
        exposureInclude = AppPreferences.getExposureIncludePatterns(activity),
        exposureExclude = AppPreferences.getExposureExcludePatterns(activity),
        mcpEnabled = AppPreferences.isMcpServerEnabled(activity),
        mcpPort = AppPreferences.getMcpServerPort(activity),
        codeServerPort = AppPreferences.getCodeServerPort(activity),
        codeServerAuthEnabled = AppPreferences.isCodeServerAuthEnabled(activity),
    )

    private fun defaults() = Snapshot(
        themeMode = ThemeMode.SYSTEM,
        fullscreen = false,
        keysBarVisible = true,
        height = KeysBarSize.MEDIUM_PLUS,
        keyWidth = KeyWidth.FULL,
        sshEnabled = false,
        sshPort = AppPreferences.DEFAULT_SSH_SERVER_PORT,
        sftpPort = AppPreferences.DEFAULT_SFTP_SERVER_PORT,
        exposureHome = true,
        exposureWorkspace = false,
        exposureShared = false,
        exposureInclude = "",
        exposureExclude = "",
        mcpEnabled = false,
        mcpPort = AppPreferences.DEFAULT_MCP_SERVER_PORT,
        codeServerPort = AppPreferences.DEFAULT_CODE_SERVER_PORT,
        codeServerAuthEnabled = false,
    )

    /** Applies every field of [snapshot] live (UI + AppPreferences for non-recreate settings) without persisting theme mode. */
    private fun applyLive(snapshot: Snapshot) {
        binding.fullscreenSwitch.setOnCheckedChangeListener(null)
        binding.showKeysBarSwitch.setOnCheckedChangeListener(null)
        binding.sshServerSwitch.setOnCheckedChangeListener(null)
        binding.mcpServerSwitch.setOnCheckedChangeListener(null)
        binding.codeServerAuthSwitch.setOnCheckedChangeListener(null)
        binding.exposureHomeCheckBox.setOnCheckedChangeListener(null)
        binding.exposureWorkspaceCheckBox.setOnCheckedChangeListener(null)
        binding.exposureSharedCheckBox.setOnCheckedChangeListener(null)

        AppPreferences.setFullscreenEnabled(activity, snapshot.fullscreen)
        applyFullscreen()
        binding.fullscreenSwitch.isChecked = snapshot.fullscreen

        AppPreferences.setKeysBarVisible(activity, snapshot.keysBarVisible)
        applyKeysBarVisibility()
        binding.showKeysBarSwitch.isChecked = snapshot.keysBarVisible

        binding.heightSpinner.setSelection(KeysBarSize.entries.indexOf(snapshot.height))
        extraKeysBar.applySize(snapshot.height)

        binding.keyWidthSpinner.setSelection(KeyWidth.entries.indexOf(snapshot.keyWidth))
        AppPreferences.setKeysBarKeyWidth(activity, snapshot.keyWidth)
        extraKeysBar.applyKeyWidth(snapshot.keyWidth)

        AppPreferences.setSshServerEnabled(activity, snapshot.sshEnabled)
        AppPreferences.setSshServerPort(activity, snapshot.sshPort)
        AppPreferences.setSftpServerPort(activity, snapshot.sftpPort)
        binding.sshServerSwitch.isChecked = snapshot.sshEnabled
        binding.sshPortEditText.setText(snapshot.sshPort.toString())
        binding.sftpPortEditText.setText(snapshot.sftpPort.toString())

        AppPreferences.setExposureHomeEnabled(activity, snapshot.exposureHome)
        AppPreferences.setExposureWorkspaceEnabled(activity, snapshot.exposureWorkspace)
        AppPreferences.setExposureSharedEnabled(activity, snapshot.exposureShared)
        AppPreferences.setExposureIncludePatterns(activity, snapshot.exposureInclude)
        AppPreferences.setExposureExcludePatterns(activity, snapshot.exposureExclude)
        binding.exposureHomeCheckBox.isChecked = snapshot.exposureHome
        binding.exposureWorkspaceCheckBox.isChecked = snapshot.exposureWorkspace
        binding.exposureSharedCheckBox.isChecked = snapshot.exposureShared
        binding.exposureIncludeEditText.setText(snapshot.exposureInclude)
        binding.exposureExcludeEditText.setText(snapshot.exposureExclude)

        AppPreferences.setMcpServerEnabled(activity, snapshot.mcpEnabled)
        AppPreferences.setMcpServerPort(activity, snapshot.mcpPort)
        binding.mcpServerSwitch.isChecked = snapshot.mcpEnabled
        binding.mcpPortEditText.setText(snapshot.mcpPort.toString())

        AppPreferences.setCodeServerAuthEnabled(activity, snapshot.codeServerAuthEnabled)
        AppPreferences.setCodeServerPort(activity, snapshot.codeServerPort)
        binding.codeServerAuthSwitch.isChecked = snapshot.codeServerAuthEnabled
        binding.codeServerPortEditText.setText(snapshot.codeServerPort.toString())

        BootstrapService.requestSshSync(activity)
        BootstrapService.requestMcpSync(activity)
        BootstrapService.requestCodeServerSync(activity)
        updateSshConnectionInfo()
        updateMcpConnectionInfo()
        updateCodeServerConnectionInfo()

        val themeChanged = AppPreferences.getEffectiveThemeMode(activity) != snapshot.themeMode
        binding.themeSpinner.setSelection(ThemeMode.entries.indexOf(snapshot.themeMode))
        if (themeChanged) {
            AppPreferences.themeModePreviewOverride = snapshot.themeMode
            AppPreferences.reopenSettingsDialogAfterRecreate = true
            activity.recreate()
        }

        binding.fullscreenSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setFullscreenEnabled(activity, checked)
            applyFullscreen()
        }
        binding.showKeysBarSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setKeysBarVisible(activity, checked)
            applyKeysBarVisibility()
        }
        binding.sshServerSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setSshServerEnabled(activity, checked)
            BootstrapService.requestSshSync(activity)
            updateSshConnectionInfo()
        }
        binding.mcpServerSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setMcpServerEnabled(activity, checked)
            BootstrapService.requestMcpSync(activity)
            updateMcpConnectionInfo()
        }
        binding.codeServerAuthSwitch.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setCodeServerAuthEnabled(activity, checked)
            BootstrapService.requestCodeServerSync(activity)
            updateCodeServerConnectionInfo()
        }
        binding.exposureHomeCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureHomeEnabled(activity, checked)
        }
        binding.exposureWorkspaceCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureWorkspaceEnabled(activity, checked)
        }
        binding.exposureSharedCheckBox.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setExposureSharedEnabled(activity, checked)
        }
        refreshSshPasswordField()
        refreshMcpApiKeyField()
        refreshCodeServerPasswordField()
    }

    /** Writes every currently-live value (including a pending theme preview, if any) to SharedPreferences. */
    private fun persistCurrentLiveState() {
        AppPreferences.setFullscreenEnabled(activity, binding.fullscreenSwitch.isChecked)
        AppPreferences.setKeysBarVisible(activity, binding.showKeysBarSwitch.isChecked)
        AppPreferences.setKeysBarSize(activity, KeysBarSize.entries[binding.heightSpinner.selectedItemPosition])
        AppPreferences.setKeysBarKeyWidth(activity, KeyWidth.entries[binding.keyWidthSpinner.selectedItemPosition])
        applySshPortFromField()
        applySftpPortFromField()
        applySshPasswordFromField()
        AppPreferences.setSshServerEnabled(activity, binding.sshServerSwitch.isChecked)
        AppPreferences.setSshServerPort(activity, currentSshPortFieldValue())
        AppPreferences.setSftpServerPort(activity, currentSftpPortFieldValue())
        AppPreferences.setExposureHomeEnabled(activity, binding.exposureHomeCheckBox.isChecked)
        AppPreferences.setExposureWorkspaceEnabled(activity, binding.exposureWorkspaceCheckBox.isChecked)
        AppPreferences.setExposureSharedEnabled(activity, binding.exposureSharedCheckBox.isChecked)
        AppPreferences.setExposureIncludePatterns(activity, binding.exposureIncludeEditText.text.toString())
        AppPreferences.setExposureExcludePatterns(activity, binding.exposureExcludeEditText.text.toString())
        applyMcpPortFromField()
        applyMcpApiKeyFromField()
        AppPreferences.setMcpServerEnabled(activity, binding.mcpServerSwitch.isChecked)
        AppPreferences.setMcpServerPort(activity, currentMcpPortFieldValue())
        applyCodeServerPortFromField()
        applyCodeServerPasswordFromField()
        AppPreferences.setCodeServerAuthEnabled(activity, binding.codeServerAuthSwitch.isChecked)
        AppPreferences.setCodeServerPort(activity, currentCodeServerPortFieldValue())
        val pendingTheme = AppPreferences.themeModePreviewOverride
        if (pendingTheme != null) {
            AppPreferences.setThemeMode(activity, pendingTheme)
            AppPreferences.themeModePreviewOverride = null
        }
    }

    private fun currentSshPortFieldValue(): Int =
        binding.sshPortEditText.text.toString().toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: AppPreferences.getSshServerPort(activity)

    /** Applies the port field's current value live (and re-syncs sshd) once the user leaves the field. */
    private fun applySshPortFromField() {
        val port = currentSshPortFieldValue()
        binding.sshPortEditText.setText(port.toString())
        if (port != AppPreferences.getSshServerPort(activity)) {
            AppPreferences.setSshServerPort(activity, port)
            BootstrapService.requestSshSync(activity)
        }
        updateSshConnectionInfo()
    }

    private fun currentSftpPortFieldValue(): Int =
        binding.sftpPortEditText.text.toString().toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: AppPreferences.getSftpServerPort(activity)

    /** Applies the SFTP port field's current value live (and re-syncs sshd, which serves SFTP on this port too) once the user leaves the field. */
    private fun applySftpPortFromField() {
        val port = currentSftpPortFieldValue()
        binding.sftpPortEditText.setText(port.toString())
        if (port != AppPreferences.getSftpServerPort(activity)) {
            AppPreferences.setSftpServerPort(activity, port)
            BootstrapService.requestSshSync(activity)
        }
        updateSshConnectionInfo()
    }

    private fun updateSshConnectionInfo() {
        val enabled = AppPreferences.isSshServerEnabled(activity)
        binding.sshConnectionInfoText.text = if (enabled) RemoteAccessActions.sshCommand(activity) else ""
        binding.sftpConnectionInfoText.text = if (enabled) RemoteAccessActions.sftpCommand(activity) else ""
    }

    /**
     * Shared confirmation step for the dice-icon "generate random value"
     * action on the SSH/MCP/code-server secret fields (see
     * [android.widget.Toast]-followed callers above) -- mirrors KeePassDX's
     * own generate-with-confirmation UX, since silently replacing a
     * password/API key an already-connected client is relying on would be
     * an easy way to lock yourself out by accident. Defaults to Cancel.
     */
    private fun confirmGenerate(messageRes: Int, onConfirmed: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(activity, R.style.ThemeOverlay_CoDebian_AlertDialog)
            .setTitle(R.string.generate_confirm_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.generate_confirm_positive) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.generate_confirm_negative, null)
            .show()
    }

    /** Reflects the currently-active SSH password (auto-generated or custom) in the field, e.g. after opening or regenerating. */
    private fun refreshSshPasswordField() {
        binding.sshCustomPasswordEditText.setText(AppPreferences.getOrCreateSshServerPassword(activity))
    }

    /** Applies the password field's current value live (as a custom password) once the user leaves the field, if it actually changed. */
    private fun applySshPasswordFromField() {
        val typed = binding.sshCustomPasswordEditText.text.toString()
        if (typed.isBlank()) {
            // Blank means "go back to auto-generated"; reflect the freshly
            // generated one back into the field rather than leaving it empty.
            refreshSshPasswordField()
            return
        }
        if (typed != AppPreferences.getOrCreateSshServerPassword(activity)) {
            AppPreferences.setCustomSshPassword(activity, typed)
            BootstrapService.requestSshSync(activity)
        }
    }

    private fun currentMcpPortFieldValue(): Int =
        binding.mcpPortEditText.text.toString().toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: AppPreferences.getMcpServerPort(activity)

    /** Applies the port field's current value live (and re-syncs mcp-proxy) once the user leaves the field. */
    private fun applyMcpPortFromField() {
        val port = currentMcpPortFieldValue()
        binding.mcpPortEditText.setText(port.toString())
        if (port != AppPreferences.getMcpServerPort(activity)) {
            AppPreferences.setMcpServerPort(activity, port)
            BootstrapService.requestMcpSync(activity)
        }
        updateMcpConnectionInfo()
    }

    private fun updateMcpConnectionInfo() {
        binding.mcpConnectionInfoText.text = if (AppPreferences.isMcpServerEnabled(activity)) {
            FtpMcpActions.mcpUrl(activity)
        } else {
            ""
        }
    }

    /** Reflects the currently-active MCP API key (auto-generated or custom) in the field, e.g. after opening or regenerating. */
    private fun refreshMcpApiKeyField() {
        binding.mcpCustomApiKeyEditText.setText(AppPreferences.getOrCreateMcpApiKey(activity))
    }

    /** Applies the API key field's current value live (as a custom key) once the user leaves the field, if it actually changed. */
    private fun applyMcpApiKeyFromField() {
        val typed = binding.mcpCustomApiKeyEditText.text.toString()
        if (typed.isBlank()) {
            refreshMcpApiKeyField()
            return
        }
        if (typed != AppPreferences.getOrCreateMcpApiKey(activity)) {
            AppPreferences.setCustomMcpApiKey(activity, typed)
            BootstrapService.requestMcpSync(activity)
        }
    }

    private fun currentCodeServerPortFieldValue(): Int =
        binding.codeServerPortEditText.text.toString().toIntOrNull()
            ?.coerceIn(1, 65535)
            ?: AppPreferences.getCodeServerPort(activity)

    /** Applies the port field's current value live (and restarts+reloads code-server) once the user leaves the field. */
    private fun applyCodeServerPortFromField() {
        val port = currentCodeServerPortFieldValue()
        binding.codeServerPortEditText.setText(port.toString())
        if (port != AppPreferences.getCodeServerPort(activity)) {
            AppPreferences.setCodeServerPort(activity, port)
            BootstrapService.requestCodeServerSync(activity)
        }
        updateCodeServerConnectionInfo()
    }

    private fun updateCodeServerConnectionInfo() {
        binding.codeServerConnectionInfoText.text = FtpMcpActions.codeServerUrl(activity)
    }

    /** Reflects the currently-active code-server password (auto-generated or custom) in the field, e.g. after opening or regenerating. */
    private fun refreshCodeServerPasswordField() {
        binding.codeServerCustomPasswordEditText.setText(AppPreferences.getOrCreateCodeServerPassword(activity))
    }

    /** Applies the password field's current value live (as a custom password) once the user leaves the field, if it actually changed. */
    private fun applyCodeServerPasswordFromField() {
        val typed = binding.codeServerCustomPasswordEditText.text.toString()
        if (typed.isBlank()) {
            refreshCodeServerPasswordField()
            return
        }
        if (typed != AppPreferences.getOrCreateCodeServerPassword(activity)) {
            AppPreferences.setCustomCodeServerPassword(activity, typed)
            if (AppPreferences.isCodeServerAuthEnabled(activity)) BootstrapService.requestCodeServerSync(activity)
        }
    }

    private fun setupThemeSpinner() {
        val labels = ThemeMode.entries.map { activity.getString(it.labelResId) }
        binding.themeSpinner.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, labels,
        )
        binding.themeSpinner.onItemSelectedListener = spinnerListener { position ->
            val mode = ThemeMode.entries[position]
            if (mode != AppPreferences.getEffectiveThemeMode(activity)) {
                AppPreferences.themeModePreviewOverride = mode
                AppPreferences.reopenSettingsDialogAfterRecreate = true
                activity.recreate()
            }
        }
    }

    private fun setupHeightSpinner() {
        val labels = KeysBarSize.entries.map { activity.getString(it.labelResId) }
        binding.heightSpinner.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, labels,
        )
        binding.heightSpinner.onItemSelectedListener = spinnerListener { position ->
            extraKeysBar.applySize(KeysBarSize.entries[position])
        }
    }

    private fun setupKeyWidthSpinner() {
        val labels = KeyWidth.entries.map { activity.getString(it.labelResId) }
        binding.keyWidthSpinner.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, labels,
        )
        binding.keyWidthSpinner.onItemSelectedListener = spinnerListener { position ->
            val width = KeyWidth.entries[position]
            AppPreferences.setKeysBarKeyWidth(activity, width)
            extraKeysBar.applyKeyWidth(width)
        }
    }

    /** Ignores the initial programmatic setSelection() callback so only real user taps trigger a live-apply. */
    private fun spinnerListener(onUserSelected: (Int) -> Unit): android.widget.AdapterView.OnItemSelectedListener =
        object : android.widget.AdapterView.OnItemSelectedListener {
            private var firstCall = true
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (firstCall) {
                    firstCall = false
                    return
                }
                onUserSelected(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
}
