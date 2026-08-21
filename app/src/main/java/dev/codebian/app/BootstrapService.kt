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
        // Start with a fresh on-screen bootstrap console for this run
        BootstrapManager.clearLogs()

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
