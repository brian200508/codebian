package dev.codebian.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Foreground service that owns the whole self-contained runtime lifecycle:
 * download the Debian rootfs (first run only) -> extract it -> install
 * code-server inside it via proot -> launch code-server bound to
 * 127.0.0.1 -> report readiness through [BootstrapManager].
 *
 * Everything happens inside our own process/rootfs; there is no Termux
 * dependency and no X server/VNC involved at any point.
 */
class BootstrapService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var proot: ProotRuntime
    private var codeServerProcess: Process? = null
    private var lastCodeServerConfigKey: String? = null
    private var sshdProcess: Process? = null
    private var lastSshConfigKey: String? = null
    private var mcpProxyProcess: Process? = null
    private var lastMcpPort: Int? = null
    private var lastMcpExposureKey: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Body text of the currently-shown notification, kept so [ACTION_TOGGLE_WAKELOCK] can rebuild it without needing to know the current bootstrap status text. */
    private var lastNotificationText: String = ""

    override fun onCreate() {
        super.onCreate()
        proot = ProotRuntime(this)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_bootstrapping)))
        instance = this
        applyWakeLockSetting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC_SSH -> scope.launch { syncSshServerState() }
            ACTION_SYNC_MCP -> scope.launch { syncMcpServerState() }
            ACTION_SYNC_CODE_SERVER -> scope.launch { syncCodeServerState() }
            ACTION_UPDATE_CODE_SERVER -> scope.launch { updateCodeServer() }
            ACTION_TOGGLE_WAKELOCK -> {
                AppPreferences.setWakeLockEnabled(this, !AppPreferences.isWakeLockEnabled(this))
                applyWakeLockSetting()
                updateNotification(lastNotificationText)
            }
            ACTION_EXIT -> {
                // Mirrors the floating menu button's confirmed Exit action
                // (stop every server, nothing left running), but triggered
                // straight from the notification -- like Termux's own
                // notification Exit button -- without needing the app UI
                // open at all. Killing this process afterwards also drops
                // any Activity that happens to be in the foreground, since
                // there is no UI-side receiver to ask it to close itself.
                stopServerProcesses()
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            else -> scope.launch { runBootstrap() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServerProcesses()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (instance === this) instance = null
        job.cancel()
        super.onDestroy()
    }

    private fun stopServerProcesses() {
        codeServerProcess?.destroy()
        codeServerProcess = null
        sshdProcess?.destroy()
        sshdProcess = null
        mcpProxyProcess?.destroy()
        mcpProxyProcess = null
    }

    /**
     * Acquires or releases a partial wake lock (keeps the CPU running; the
     * screen is still free to turn off/lock normally) to match
     * [AppPreferences.isWakeLockEnabled], so code-server/sshd keep
     * responding to requests (e.g. from another device over SSH) even
     * while the screen is off. Idempotent -- safe to call repeatedly, e.g.
     * every time the Settings dialog's toggle changes, as well as once
     * during [onCreate].
     */
    private fun applyWakeLockSetting() {
        val enabled = AppPreferences.isWakeLockEnabled(this)
        if (enabled) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CoDebian:BootstrapService").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private suspend fun runBootstrap() {
        try {
            if (!proot.isRootfsInstalled) {
                downloadAndExtractRootfs()
            }
            BootstrapManager.update(BootstrapState.InstallingCodeServer)
            ensureCodeServerInstalled()
            ensureBundledToolsInstalled()
            ensureUserProvisioned()

            BootstrapManager.update(BootstrapState.StartingServer)
            syncCodeServerState()

            syncSshServerState()
            syncMcpServerState()

            updateNotification(getString(R.string.status_ready))
        } catch (t: Throwable) {
            BootstrapManager.update(BootstrapState.Error(t.message ?: t.toString()))
        }
    }

    private fun downloadAndExtractRootfs() {
        BootstrapManager.update(BootstrapState.Downloading("Debian rootfs", 0))
        val archive = File(cacheDir, "rootfs.tar.gz")
        val registry = DockerRegistryClient()
        val layer = registry.resolveLayer(
            RemoteAssets.DEBIAN_REPOSITORY, RemoteAssets.DEBIAN_TAG,
            RemoteAssets.DEBIAN_ARCH, RemoteAssets.DEBIAN_VARIANT,
        )
        check(layer.mediaType.endsWith("tar+gzip")) {
            "Unexpected layer mediaType ${layer.mediaType} -- extraction below only handles gzip"
        }
        registry.downloadLayer(RemoteAssets.DEBIAN_REPOSITORY, layer, archive) { percent ->
            BootstrapManager.update(BootstrapState.Downloading("Debian rootfs", percent))
        }

        BootstrapManager.update(BootstrapState.VerifyingDownload)
        if (!registry.verifyDigest(archive, layer.digest)) {
            archive.delete()
            error("Downloaded rootfs failed SHA-256 verification against ${layer.digest} -- refusing to extract/execute a corrupted or tampered image")
        }

        BootstrapManager.update(BootstrapState.ExtractingRootfs)
        val target = proot.extractRootfsTarget()
        val targetCanonical = target.canonicalFile
        GzipCompressorInputStream(archive.inputStream().buffered()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val current = entry
                    val outFile = File(target, current.name)
                    // Guard against a malicious/corrupt tar entry (e.g. "../../x")
                    // escaping the rootfs directory via path traversal. The
                    // rootfs layer's own root entry ("./") canonicalises to
                    // exactly targetCanonical itself (not a subpath of it),
                    // so that exact match must be allowed too -- only a path
                    // that is neither the root nor strictly under it is
                    // actually a traversal attempt.
                    val outCanonicalPath = outFile.canonicalFile.path
                    check(
                        outCanonicalPath == targetCanonical.path ||
                            outCanonicalPath.startsWith(targetCanonical.path + File.separator)
                    ) {
                        "Refusing to extract tar entry outside rootfs: ${current.name}"
                    }
                    if (current.isDirectory) {
                        outFile.mkdirs()
                    } else if (current.isSymbolicLink) {
                        // commons-compress doesn't materialise symlinks itself;
                        // Debian's rootfs layer has plenty (e.g. /bin -> usr/bin).
                        outFile.parentFile?.mkdirs()
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(), File(current.linkName).toPath(),
                            )
                        } catch (_: java.nio.file.FileAlreadyExistsException) {
                            // Re-extraction after a partial/interrupted run.
                        }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                        if (current.mode and 0b001_000_000 != 0) outFile.setExecutable(true)
                    }
                    entry = tar.nextEntry
                }
            }
        }
        archive.delete()
    }

    private fun ensureCodeServerInstalled() {
        val marker = File(proot.extractRootfsTarget(), "usr/bin/code-server")
        if (marker.exists()) return
        val exit = proot.runInRootfs(
            listOf(
                "bash", "-lc",
                "apt-get update && " +
                    "apt-get install -y curl ca-certificates gnupg && " +
                    "curl -fsSL https://code-server.dev/install.sh | sh"
            )
        )
        check(exit == 0) { "code-server install failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * Re-runs the official code-server install script to fetch and install
     * whatever the latest release currently is, overwriting the existing
     * install. Triggered on demand via [requestCodeServerUpdate] (e.g. an
     * "Update code-server" button in Settings) rather than automatically,
     * since it briefly restarts the code-server process.
     *
     * code-server does not publish an official apt repository (verified:
     * its install.sh detects Debian/Ubuntu and downloads/installs the
     * latest .deb from GitHub via `dpkg -i`, a one-off install rather than
     * something `apt upgrade` would ever pick up on its own) -- re-running
     * the same install script is the straightforward, idempotent way to
     * pull in newer releases.
     */
    private suspend fun updateCodeServer() {
        try {
            BootstrapManager.update(BootstrapState.UpdatingCodeServer)
            codeServerProcess?.destroy()
            codeServerProcess = null
            val exit = proot.runInRootfs(
                listOf("bash", "-lc", "curl -fsSL https://code-server.dev/install.sh | sh")
            )
            check(exit == 0) { "code-server update failed (exit $exit) -- see logcat tag CoDebianProot" }
            BootstrapManager.update(BootstrapState.StartingServer)
            syncCodeServerState()
        } catch (t: Throwable) {
            BootstrapManager.update(BootstrapState.Error(t.message ?: t.toString()))
        }
    }

    /**
     * Installs a small set of commonly-needed dev tools, each step
     * idempotent (marked by its own sentinel file) and reported separately
     * via [BootstrapManager] so [MainActivity] can render a step-by-step
     * checklist, and so an interrupted run only re-does the remaining
     * tools rather than starting over. Node.js comes from NodeSource's
     * setup script since Debian's own apt-provided `nodejs` package is
     * typically far behind current LTS.
     */
    private fun ensureBundledToolsInstalled() {
        ensureAptPackageInstalled(BootstrapTool.GIT, "root/.codebian-git-installed", "apt-get update; apt-get install -y git")
        ensureAptPackageInstalled(BootstrapTool.PYTHON, "root/.codebian-python-installed", "apt-get update; apt-get install -y python3 python3-pip")
        ensureAptPackageInstalled(
            BootstrapTool.NODEJS, "root/.codebian-nodejs-installed",
            "apt-get update; apt-get install -y curl ca-certificates gnupg; " +
                "curl -fsSL https://deb.nodesource.com/setup_lts.x | bash -; apt-get install -y nodejs",
        )
    }

    private fun ensureAptPackageInstalled(tool: String, markerPath: String, installCommand: String) {
        val marker = File(proot.extractRootfsTarget(), markerPath)
        if (marker.exists()) return
        BootstrapManager.update(BootstrapState.InstallingBundledTools(tool))
        val exit = proot.runInRootfs(
            listOf("bash", "-lc", "set -e; $installCommand; touch /$markerPath")
        )
        check(exit == 0) { "$tool install failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * Creates the regular non-root [ProotRuntime.defaultUser] account with
     * passwordless sudo the first time (idempotent on every later launch),
     * and one-time migrates any pre-existing files from /root -- the old
     * everything-runs-as-root layout used through app version 0.0.3 -- into
     * the new user's home so upgrading users don't lose work in progress.
     */
    private fun ensureUserProvisioned() {
        val user = proot.defaultUser
        val home = proot.defaultUserHome
        val exit = proot.runInRootfs(
            listOf(
                "bash", "-lc",
                "set -e; " +
                    "id $user >/dev/null 2>&1 || useradd -m -s /bin/bash $user; " +
                    "dpkg -s sudo >/dev/null 2>&1 || (apt-get update && apt-get install -y sudo); " +
                    "mkdir -p /etc/sudoers.d; " +
                    "echo '$user ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/$user; " +
                    "chmod 0440 /etc/sudoers.d/$user; " +
                    "if [ ! -f /root/.codebian-user-migrated ]; then " +
                    "  find /root -mindepth 1 -maxdepth 1 ! -name '.codebian-user-migrated' " +
                    "    -exec cp -a {} $home/ \\; 2>/dev/null || true; " +
                    "  chown -R $user:$user $home; " +
                    "  touch /root/.codebian-user-migrated; " +
                    "fi"
            )
        )
        check(exit == 0) { "user provisioning failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * Installs openssh-server (idempotent) and generates host keys the first
     * time it's needed, then starts or stops the sshd process to match the
     * currently persisted [AppPreferences.isSshServerEnabled]/ports/password.
     * Safe to call repeatedly (e.g. every time the Settings dialog's SSH
     * switch/ports/password changes) as well as once during the initial
     * bootstrap.
     *
     * The guard below fingerprints the *whole* relevant config (both ports
     * plus the password), not just the port -- comparing only the port was
     * a real bug: regenerating/pasting a new SSH password while sshd was
     * already running on the same port used to silently skip the
     * `chpasswd` call below entirely, so the new password was saved and
     * copied to the clipboard but never actually applied to the OS
     * account, leaving the *old* password as the only one that still
     * worked.
     */
    private fun syncSshServerState() {
        if (!proot.isRootfsInstalled) return // full runBootstrap() will call this again once ready
        val enabled = AppPreferences.isSshServerEnabled(this)
        if (!enabled) {
            sshdProcess?.destroy()
            sshdProcess = null
            lastSshConfigKey = null
            return
        }
        val sshPort = AppPreferences.getSshServerPort(this)
        val sftpPort = AppPreferences.getSftpServerPort(this)
        val password = AppPreferences.getOrCreateSshServerPassword(this)
        val configKey = "$sshPort|$sftpPort|$password"
        if (sshdProcess != null && lastSshConfigKey == configKey) return // already running with this exact config
        sshdProcess?.destroy()
        ensureSshServerInstalled()
        writeSshdConfig(sshPort, sftpPort)
        proot.runInRootfs(
            listOf("bash", "-lc", "echo '${proot.defaultUser}:$password' | chpasswd")
        )
        sshdProcess = proot.startProcessInRootfs(
            listOf("bash", "-lc", "mkdir -p /run/sshd && /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config_codebian")
        )
        lastSshConfigKey = configKey
    }

    private fun ensureSshServerInstalled() {
        val marker = File(proot.extractRootfsTarget(), "usr/sbin/sshd")
        if (marker.exists()) return
        val exit = proot.runInRootfs(
            listOf("bash", "-lc", "apt-get update && apt-get install -y openssh-server")
        )
        check(exit == 0) { "openssh-server install failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * A dedicated config file (rather than editing the package's own
     * /etc/ssh/sshd_config) so re-running this on every settings change is
     * simple and idempotent, and never fights with anything `apt upgrade`
     * does to the stock file. Bound to 127.0.0.1 only by default: this is
     * meant for on-device terminal apps (e.g. Termux) talking to the same
     * device's loopback interface, not exposing a shell to the LAN/internet.
     *
     * [sshPort] and [sftpPort] are emitted as two separate `Port` lines --
     * sshd supports listening on any number of ports simultaneously with a
     * single process/account/password, which is exactly what backs the
     * merged "SSH / SFTP" settings tab: SFTP is just this same sshd's
     * `Subsystem sftp` on its own port, never a second credential. If the
     * user sets both fields to the same port, only one `Port` line is
     * written (a duplicate would make sshd fail to bind the second one).
     */
    private fun writeSshdConfig(sshPort: Int, sftpPort: Int) {
        val user = proot.defaultUser
        val portLines = listOf(sshPort, sftpPort).distinct().joinToString("\n") { "Port $it" }
        val config = """
            $portLines
            ListenAddress 127.0.0.1
            PermitRootLogin no
            PasswordAuthentication yes
            KbdInteractiveAuthentication no
            AllowUsers $user
            Subsystem sftp /usr/lib/openssh/sftp-server
            PidFile /run/sshd_codebian.pid
        """.trimIndent()
        val exit = proot.runInRootfs(
            listOf(
                "bash", "-lc",
                "ssh-keygen -A && cat > /etc/ssh/sshd_config_codebian << 'EOF'\n$config\nEOF"
            )
        )
        check(exit == 0) { "writing sshd config failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * Installs the MCP filesystem server + mcp-proxy (via npm, idempotent)
     * and starts/stops the bridged stdio->HTTP process to match
     * [AppPreferences.isMcpServerEnabled]/port/[ExposureActions] state.
     * Restarted (not live-reconfigured) whenever the port, API key, or
     * exposure config changes -- mcp-proxy has no config-reload mechanism,
     * only process restart.
     */
    private fun syncMcpServerState() {
        if (!proot.isRootfsInstalled) return
        val enabled = AppPreferences.isMcpServerEnabled(this) && ExposureActions.hasAnyExposure(this)
        val port = AppPreferences.getMcpServerPort(this)
        val apiKey = AppPreferences.getOrCreateMcpApiKey(this)
        val exposureKey = ExposureActions.exposureBindsFor(this).toString()
        val stateKey = "$port|$apiKey|$exposureKey"
        if (!enabled) {
            mcpProxyProcess?.destroy()
            mcpProxyProcess = null
            return
        }
        if (mcpProxyProcess != null && lastMcpExposureKey == stateKey) return
        mcpProxyProcess?.destroy()
        ensureMcpToolsInstalled()
        val binds = ExposureActions.exposureBindsFor(this)
        mcpProxyProcess = proot.startProcessInRootfs(
            listOf(
                "bash", "-lc",
                "mkdir -p /mnt/codebian-exposure && mcp-proxy --port $port --apiKey '$apiKey' -- " +
                    "npx -y @modelcontextprotocol/server-filesystem ${ExposureActions.EXPOSURE_ROOT_GUEST_PATH}"
            ),
            extraBinds = binds,
        )
        lastMcpPort = port
        lastMcpExposureKey = stateKey
    }

    private fun ensureMcpToolsInstalled() {
        val marker = File(proot.extractRootfsTarget(), "root/.codebian-mcp-installed")
        if (marker.exists()) return
        // Node.js/npm already provisioned by ensureBundledToolsInstalled(); this
        // just adds the two small MCP packages globally so `npx` resolves them
        // without a network fetch on every server start.
        val exit = proot.runInRootfs(
            listOf(
                "bash", "-lc",
                "npm install -g @modelcontextprotocol/server-filesystem mcp-proxy && touch /root/.codebian-mcp-installed"
            )
        )
        check(exit == 0) { "MCP filesystem server install failed (exit $exit) -- see logcat tag CoDebianProot" }
    }

    /**
     * Starts (or restarts, if the port/auth/password changed) code-server
     * to match [AppPreferences.getCodeServerPort]/
     * [AppPreferences.isCodeServerAuthEnabled]/
     * [AppPreferences.getOrCreateCodeServerPassword], then waits for the
     * port to open and publishes a fresh [BootstrapState.Ready] so
     * [MainActivity] points its WebView at the (possibly new) port --
     * plain `--auth none` gives no login prompt at all, so this is
     * restarted (never live-reconfigured) exactly like MCP: code-server
     * has no signal to reload `--bind-addr`/`--auth` on a running process.
     */
    private fun syncCodeServerState() {
        if (!proot.isRootfsInstalled) return
        val port = AppPreferences.getCodeServerPort(this)
        val authEnabled = AppPreferences.isCodeServerAuthEnabled(this)
        val password = if (authEnabled) AppPreferences.getOrCreateCodeServerPassword(this) else null
        val configKey = "$port|$authEnabled|$password"
        if (codeServerProcess != null && lastCodeServerConfigKey == configKey) {
            waitForPort(port)
            BootstrapManager.update(BootstrapState.Ready(port))
            return
        }
        val wasAlreadyRunning = codeServerProcess != null
        if (wasAlreadyRunning) BootstrapManager.update(BootstrapState.RestartingCodeServer)
        codeServerProcess?.destroy()
        val script = if (authEnabled && password != null) {
            // Exported inline in the command itself (rather than via
            // ProcessBuilder env vars) since proot's `env -i` wrapper wipes
            // everything except the handful of vars it explicitly forwards.
            "PASSWORD='$password' code-server --bind-addr 127.0.0.1:$port --auth password ${proot.defaultUserHome}"
        } else {
            "code-server --bind-addr 127.0.0.1:$port --auth none ${proot.defaultUserHome}"
        }
        codeServerProcess = proot.startProcessInRootfs(
            listOf("bash", "-lc", script),
            runAsUser = proot.defaultUser,
        )
        lastCodeServerConfigKey = configKey
        waitForPort(port)
        BootstrapManager.update(BootstrapState.Ready(port))
    }

    private fun waitForPort(port: Int, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use {
                    it.connect(InetSocketAddress("127.0.0.1", port), 500)
                    return
                }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        error("code-server did not open port $port within ${timeoutMs}ms")
    }

    private fun buildNotification(text: String): Notification {
        lastNotificationText = text
        val channelId = "codebian-bootstrap"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "CoDebian runtime", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val wakeLockLabel = if (AppPreferences.isWakeLockEnabled(this)) {
            getString(R.string.notification_action_wake_lock_on)
        } else {
            getString(R.string.notification_action_wake_lock_off)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .addAction(0, wakeLockLabel, servicePendingIntent(ACTION_TOGGLE_WAKELOCK, 1))
            .addAction(0, getString(R.string.notification_action_exit), servicePendingIntent(ACTION_EXIT, 2))
            .build()
    }

    /** Builds a [PendingIntent] that redelivers [action] to this same service's [onStartCommand], for the notification's action buttons. */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BootstrapService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_SYNC_SSH = "dev.codebian.app.action.SYNC_SSH"
        private const val ACTION_SYNC_MCP = "dev.codebian.app.action.SYNC_MCP"
        private const val ACTION_SYNC_CODE_SERVER = "dev.codebian.app.action.SYNC_CODE_SERVER"
        private const val ACTION_UPDATE_CODE_SERVER = "dev.codebian.app.action.UPDATE_CODE_SERVER"
        private const val ACTION_TOGGLE_WAKELOCK = "dev.codebian.app.action.TOGGLE_WAKELOCK"
        private const val ACTION_EXIT = "dev.codebian.app.action.EXIT"

        @Volatile
        private var instance: BootstrapService? = null

        /**
         * Called by [SettingsDialog] right after the user toggles SSH
         * on/off or changes its port, so the change takes effect immediately
         * without needing to restart the app -- consistent with every other
         * setting's live-apply behavior. If the service isn't running yet
         * (e.g. app was killed), this simply starts it, and the normal
         * bootstrap path will pick up the persisted SSH settings itself.
         */
        fun requestSshSync(context: android.content.Context) {
            val running = instance
            if (running != null) {
                running.scope.launch { running.syncSshServerState() }
            } else {
                // Service isn't alive (e.g. killed by the system) -- start it
                // normally; runBootstrap() is idempotent and calls
                // syncSshServerState() itself once the rootfs/user are ready.
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, BootstrapService::class.java),
                )
            }
        }

        /**
         * Called by the floating menu button's "Exit" quick action after the
         * user confirms: stops code-server/sshd (if running) and stops this
         * foreground service, so no rootfs processes are left running in
         * the background once the app itself exits. Synchronous (not
         * launched on [scope]) since the caller is about to immediately
         * finish the Activity and kill the process -- there's nothing to
         * await asynchronously here.
         */
        fun stopAllServers(context: android.content.Context) {
            instance?.stopServerProcesses()
            context.stopService(Intent(context, BootstrapService::class.java))
        }

        /**
         * Triggered by the Settings dialog's "Update code-server" button.
         * Requires the service to already be running (bootstrap complete);
         * if it isn't, there is nothing installed yet to update.
         */
        fun requestCodeServerUpdate(context: android.content.Context) {
            val running = instance ?: return
            running.scope.launch { running.updateCodeServer() }
        }

        /** Same live-apply pattern as [requestSshSync], for the MCP filesystem server's enable switch/port/API key. */
        fun requestMcpSync(context: android.content.Context) {
            val running = instance
            if (running != null) {
                running.scope.launch { running.syncMcpServerState() }
            } else {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, BootstrapService::class.java),
                )
            }
        }

        /**
         * Same live-apply pattern as [requestSshSync], for code-server's
         * port/auth-enabled switch/password. Unlike SSH/MCP this briefly
         * interrupts the WebView's own connection (code-server *is* the
         * WebView's content), so [MainActivity] shows a
         * [BootstrapState.RestartingCodeServer] overlay for the brief gap
         * instead of a jarring "connection refused" flash.
         */
        fun requestCodeServerSync(context: android.content.Context) {
            val running = instance
            if (running != null) {
                running.scope.launch { running.syncCodeServerState() }
            } else {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, BootstrapService::class.java),
                )
            }
        }

        /**
         * Called after the Settings dialog's "Refresh Exposure" button, or
         * right after enabling MCP for the first time -- rebuilds the
         * filtered exposure caches (if include/exclude patterns are set) and
         * restarts MCP if currently enabled, since its proot binds are
         * fixed for a process's lifetime.
         */
        fun requestExposureRefresh(context: android.content.Context) {
            val running = instance ?: return
            running.scope.launch {
                ExposureActions.refreshNow(context)
                running.syncMcpServerState()
            }
        }

        /**
         * Called by [SettingsDialog] right after the user toggles the wake
         * lock switch, so it takes effect immediately. If the service isn't
         * running (e.g. app was killed), there's nothing to hold a wake
         * lock for yet -- [onCreate] applies the persisted setting itself
         * once it starts.
         */
        fun requestWakeLockSync(context: android.content.Context) {
            instance?.applyWakeLockSetting()
        }
    }
}
