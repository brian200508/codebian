package dev.codebian.app

import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Settings dialog "Backup" actions: export every [AppPreferences] value to a
 * single JSON file (via SAF ACTION_CREATE_DOCUMENT) and import/apply it back
 * (via ACTION_OPEN_DOCUMENT). Plain settings (toggles, ports, enum choices)
 * are stored as-is; secrets (currently just the SSH server password) are
 * encrypted with a key derived from a user-entered passphrase (PBKDF2WithHmacSHA256
 * -> AES-256-GCM), *not* the device's Android Keystore -- unlike
 * [SecureStorage] (which is intentionally device-bound), this backup is
 * meant to be portable across devices/reinstalls, and a Keystore-wrapped key
 * cannot be exported/reimported at all. The same passphrase must be supplied
 * again on import to decrypt.
 *
 * Deliberately excludes a few persisted keys that don't make sense to
 * restore: the FAB's dragged screen position (device/screen-size specific)
 * and the linked SAF workspace-tree Uri (a SAF grant is tied to the
 * originating device/picker session and cannot be replayed on another
 * device or after a reinstall -- the user must re-pick the workspace folder
 * instead).
 *
 * Must be constructed during the host Activity's onCreate() (before
 * onStart()), same as [SafActions], since registerForActivityResult()
 * requires that.
 */
class ConfigBackupActions(private val activity: AppCompatActivity) {

    /** Set right before launching [createDocumentLauncher]; consumed by its callback. */
    private var pendingExportJson: String? = null

    /** Invoked after a successful import so the currently-open Settings dialog can refresh its live fields. */
    var onImported: (() -> Unit)? = null

    private val createDocumentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingExportJson
            pendingExportJson = null
            if (uri != null && json != null) writeJsonTo(json, uri)
        }

    private val openDocumentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readJsonFrom(uri)
        }

    /** "Export Config" button: prompts for a passphrase, then a SAF destination, then writes the backup JSON. */
    fun exportConfig() {
        promptPassphrase(R.string.config_backup_export_passphrase_title) { passphrase ->
            pendingExportJson = buildExportJson(passphrase)
            createDocumentLauncher.launch(DEFAULT_EXPORT_FILE_NAME)
        }
    }

    /** "Import Config" button: pick a previously exported JSON file, then prompt for its passphrase to decrypt secrets. */
    fun importConfig() {
        openDocumentLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun writeJsonTo(json: String, uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("could not open destination")
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.config_backup_export_success_toast))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.config_backup_export_failed_toast, e.message ?: e.toString()))
                }
            }
        }
    }

    private fun readJsonFrom(uri: Uri) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = activity.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: error("could not open picked file")
                withContext(Dispatchers.Main) {
                    promptPassphrase(R.string.config_backup_import_passphrase_title) { passphrase ->
                        applyImportJson(text, passphrase)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.config_backup_import_failed_toast, e.message ?: e.toString()))
                }
            }
        }
    }

    private fun buildExportJson(passphrase: String): String {
        val prefs = JSONObject().apply {
            put(KEY_KEYS_BAR_VISIBLE, AppPreferences.isKeysBarVisible(activity))
            put(KEY_FULLSCREEN_ENABLED, AppPreferences.isFullscreenEnabled(activity))
            put(KEY_KEYS_BAR_SIZE, AppPreferences.getKeysBarSize(activity).name)
            put(KEY_KEYS_BAR_WIDTH, AppPreferences.getKeysBarKeyWidth(activity).name)
            put(KEY_THEME_MODE, AppPreferences.getThemeMode(activity).name)
            put(KEY_SSH_SERVER_ENABLED, AppPreferences.isSshServerEnabled(activity))
            put(KEY_SSH_SERVER_PORT, AppPreferences.getSshServerPort(activity))
            put(KEY_SFTP_SERVER_PORT, AppPreferences.getSftpServerPort(activity))
            put(KEY_WAKE_LOCK_ENABLED, AppPreferences.isWakeLockEnabled(activity))
            put(KEY_EXPOSURE_HOME_ENABLED, AppPreferences.isExposureHomeEnabled(activity))
            put(KEY_EXPOSURE_WORKSPACE_ENABLED, AppPreferences.isExposureWorkspaceEnabled(activity))
            put(KEY_EXPOSURE_SHARED_ENABLED, AppPreferences.isExposureSharedEnabled(activity))
            put(KEY_EXPOSURE_INCLUDE_PATTERNS, AppPreferences.getExposureIncludePatterns(activity))
            put(KEY_EXPOSURE_EXCLUDE_PATTERNS, AppPreferences.getExposureExcludePatterns(activity))
            put(KEY_MCP_SERVER_ENABLED, AppPreferences.isMcpServerEnabled(activity))
            put(KEY_MCP_SERVER_PORT, AppPreferences.getMcpServerPort(activity))
            put(KEY_CODE_SERVER_PORT, AppPreferences.getCodeServerPort(activity))
            put(KEY_CODE_SERVER_AUTH_ENABLED, AppPreferences.isCodeServerAuthEnabled(activity))
        }
        val secrets = JSONObject().apply {
            put(KEY_SSH_SERVER_PASSWORD, AppPreferences.getOrCreateSshServerPassword(activity))
            put(KEY_MCP_API_KEY, AppPreferences.getOrCreateMcpApiKey(activity))
            put(KEY_CODE_SERVER_PASSWORD, AppPreferences.getOrCreateCodeServerPassword(activity))
        }
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(secrets.toString().toByteArray(Charsets.UTF_8))
        val secretsEnc = JSONObject().apply {
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", PBKDF2_ITERATIONS)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        return JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("prefs", prefs)
            put("secretsEncrypted", secretsEnc)
        }.toString(2)
    }

    private fun applyImportJson(text: String, passphrase: String) {
        try {
            val root = JSONObject(text)
            val prefs = root.optJSONObject("prefs")
            if (prefs != null) {
                if (prefs.has(KEY_KEYS_BAR_VISIBLE)) AppPreferences.setKeysBarVisible(activity, prefs.getBoolean(KEY_KEYS_BAR_VISIBLE))
                if (prefs.has(KEY_FULLSCREEN_ENABLED)) AppPreferences.setFullscreenEnabled(activity, prefs.getBoolean(KEY_FULLSCREEN_ENABLED))
                prefs.optString(KEY_KEYS_BAR_SIZE, null)?.let { name ->
                    runCatching { KeysBarSize.valueOf(name) }.getOrNull()?.let { AppPreferences.setKeysBarSize(activity, it) }
                }
                prefs.optString(KEY_KEYS_BAR_WIDTH, null)?.let { name ->
                    runCatching { KeyWidth.valueOf(name) }.getOrNull()?.let { AppPreferences.setKeysBarKeyWidth(activity, it) }
                }
                prefs.optString(KEY_THEME_MODE, null)?.let { name ->
                    runCatching { ThemeMode.valueOf(name) }.getOrNull()?.let { AppPreferences.setThemeMode(activity, it) }
                }
                if (prefs.has(KEY_SSH_SERVER_ENABLED)) AppPreferences.setSshServerEnabled(activity, prefs.getBoolean(KEY_SSH_SERVER_ENABLED))
                if (prefs.has(KEY_SSH_SERVER_PORT)) AppPreferences.setSshServerPort(activity, prefs.getInt(KEY_SSH_SERVER_PORT))
                if (prefs.has(KEY_SFTP_SERVER_PORT)) AppPreferences.setSftpServerPort(activity, prefs.getInt(KEY_SFTP_SERVER_PORT))
                if (prefs.has(KEY_WAKE_LOCK_ENABLED)) AppPreferences.setWakeLockEnabled(activity, prefs.getBoolean(KEY_WAKE_LOCK_ENABLED))
                if (prefs.has(KEY_EXPOSURE_HOME_ENABLED)) AppPreferences.setExposureHomeEnabled(activity, prefs.getBoolean(KEY_EXPOSURE_HOME_ENABLED))
                if (prefs.has(KEY_EXPOSURE_WORKSPACE_ENABLED)) AppPreferences.setExposureWorkspaceEnabled(activity, prefs.getBoolean(KEY_EXPOSURE_WORKSPACE_ENABLED))
                if (prefs.has(KEY_EXPOSURE_SHARED_ENABLED)) AppPreferences.setExposureSharedEnabled(activity, prefs.getBoolean(KEY_EXPOSURE_SHARED_ENABLED))
                if (prefs.has(KEY_EXPOSURE_INCLUDE_PATTERNS)) AppPreferences.setExposureIncludePatterns(activity, prefs.getString(KEY_EXPOSURE_INCLUDE_PATTERNS))
                if (prefs.has(KEY_EXPOSURE_EXCLUDE_PATTERNS)) AppPreferences.setExposureExcludePatterns(activity, prefs.getString(KEY_EXPOSURE_EXCLUDE_PATTERNS))
                if (prefs.has(KEY_MCP_SERVER_ENABLED)) AppPreferences.setMcpServerEnabled(activity, prefs.getBoolean(KEY_MCP_SERVER_ENABLED))
                if (prefs.has(KEY_MCP_SERVER_PORT)) AppPreferences.setMcpServerPort(activity, prefs.getInt(KEY_MCP_SERVER_PORT))
                if (prefs.has(KEY_CODE_SERVER_PORT)) AppPreferences.setCodeServerPort(activity, prefs.getInt(KEY_CODE_SERVER_PORT))
                if (prefs.has(KEY_CODE_SERVER_AUTH_ENABLED)) AppPreferences.setCodeServerAuthEnabled(activity, prefs.getBoolean(KEY_CODE_SERVER_AUTH_ENABLED))
            }

            val secretsEnc = root.optJSONObject("secretsEncrypted")
            if (secretsEnc != null) {
                val salt = Base64.decode(secretsEnc.getString("salt"), Base64.NO_WRAP)
                val iv = Base64.decode(secretsEnc.getString("iv"), Base64.NO_WRAP)
                val ciphertext = Base64.decode(secretsEnc.getString("ciphertext"), Base64.NO_WRAP)
                val iterations = secretsEnc.optInt("iterations", PBKDF2_ITERATIONS)
                val key = deriveKey(passphrase, salt, iterations)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                }
                val secrets = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
                secrets.optString(KEY_SSH_SERVER_PASSWORD, null)?.takeIf { it.isNotBlank() }?.let {
                    AppPreferences.setCustomSshPassword(activity, it)
                }
                secrets.optString(KEY_MCP_API_KEY, null)?.takeIf { it.isNotBlank() }?.let {
                    AppPreferences.setCustomMcpApiKey(activity, it)
                }
                secrets.optString(KEY_CODE_SERVER_PASSWORD, null)?.takeIf { it.isNotBlank() }?.let {
                    AppPreferences.setCustomCodeServerPassword(activity, it)
                }
            }

            BootstrapService.requestSshSync(activity)
            BootstrapService.requestMcpSync(activity)
            BootstrapService.requestCodeServerSync(activity)
            BootstrapService.requestWakeLockSync(activity)
            toast(activity.getString(R.string.config_backup_import_success_toast))
            onImported?.invoke()
        } catch (e: Exception) {
            // Wrong passphrase surfaces here too: GCM authentication failure
            // throws AEADBadTagException, indistinguishable from a corrupted
            // file for the user's purposes -- both mean "can't recover the secrets".
            toast(activity.getString(R.string.config_backup_import_failed_toast, e.message ?: e.toString()))
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, AES_KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Shows a passphrase entry dialog; invokes [onEntered] only if the user confirms with a non-blank value. */
    private fun promptPassphrase(titleResId: Int, onEntered: (String) -> Unit) {
        val inputLayout = TextInputLayout(activity).apply {
            isPasswordVisibilityToggleEnabled = true
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }
        val input = TextInputEditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputLayout.addView(input)
        AlertDialog.Builder(activity, R.style.ThemeOverlay_CoDebian_AlertDialog)
            .setTitle(titleResId)
            .setMessage(R.string.config_backup_passphrase_message)
            .setView(inputLayout)
            .setPositiveButton(R.string.config_backup_passphrase_ok) { _, _ ->
                val passphrase = input.text?.toString().orEmpty()
                if (passphrase.isNotBlank()) onEntered(passphrase)
            }
            .setNegativeButton(R.string.exit_confirm_negative, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DEFAULT_EXPORT_FILE_NAME = "codebian-config-backup.json"
        private const val FORMAT_VERSION = 1
        private const val PBKDF2_ITERATIONS = 200_000
        private const val AES_KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_KEYS_BAR_VISIBLE = "keysBarVisible"
        private const val KEY_FULLSCREEN_ENABLED = "fullscreenEnabled"
        private const val KEY_KEYS_BAR_SIZE = "keysBarSize"
        private const val KEY_KEYS_BAR_WIDTH = "keysBarKeyWidth"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_SSH_SERVER_ENABLED = "sshServerEnabled"
        private const val KEY_SSH_SERVER_PORT = "sshServerPort"
        private const val KEY_SFTP_SERVER_PORT = "sftpServerPort"
        private const val KEY_WAKE_LOCK_ENABLED = "wakeLockEnabled"
        private const val KEY_SSH_SERVER_PASSWORD = "sshServerPassword"
        private const val KEY_EXPOSURE_HOME_ENABLED = "exposureHomeEnabled"
        private const val KEY_EXPOSURE_WORKSPACE_ENABLED = "exposureWorkspaceEnabled"
        private const val KEY_EXPOSURE_SHARED_ENABLED = "exposureSharedEnabled"
        private const val KEY_EXPOSURE_INCLUDE_PATTERNS = "exposureIncludePatterns"
        private const val KEY_EXPOSURE_EXCLUDE_PATTERNS = "exposureExcludePatterns"
        private const val KEY_MCP_SERVER_ENABLED = "mcpServerEnabled"
        private const val KEY_MCP_SERVER_PORT = "mcpServerPort"
        private const val KEY_MCP_API_KEY = "mcpApiKey"
        private const val KEY_CODE_SERVER_PORT = "codeServerPort"
        private const val KEY_CODE_SERVER_AUTH_ENABLED = "codeServerAuthEnabled"
        private const val KEY_CODE_SERVER_PASSWORD = "codeServerPassword"
    }
}
