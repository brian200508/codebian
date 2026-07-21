package dev.codebian.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Thin wrapper around invoking `proot` to get a chroot-like Debian
 * environment without root, in the style of UserLAnd/Termux -- but driven
 * entirely from *our own* app process, so there is no dependency on the
 * separately-installed Termux app (and none of its RUN_COMMAND /
 * `allow-external-apps` cross-app sandbox boundary).
 *
 * The proot binary itself must be built against Bionic (Android's libc),
 * the same way Termux's own proot package is -- a generic glibc/musl Linux
 * static build will not run correctly under Android's SELinux policy and
 * process model. See scripts/fetch-assets.ps1 for where to source it.
 *
 * Just as important: the binary must be shipped renamed as `lib*.so` under
 * `app/src/main/jniLibs/<abi>/`, NOT as a plain asset. Since Android 10,
 * files extracted into an app's private files/cache directories are
 * mounted noexec; the one location guaranteed to be exec-able is
 * `applicationInfo.nativeLibraryDir`, which is only populated by the
 * PackageManager for files packaged as JNI libraries. This is exactly how
 * Termux itself smuggles its own bootstrap binaries past that restriction.
 *
 * Termux's proot build also needs its `loader`/`loader32` helper binaries at
 * runtime (it execs them itself, via `PROOT_LOADER`/`PROOT_LOADER_32`,
 * instead of relying on ptrace's normal exec path directly) -- these ship
 * as `libproot-loader.so` / `libproot-loader32.so` alongside `libproot.so`
 * for the same noexec reason. Without them proot fails immediately with
 * "Cannot exec loader".
 */
class ProotRuntime(private val context: Context) {

    private val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val tmpDir: File get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /**
     * Regular (non-root) Debian user that the interactive shell and
     * code-server itself run as -- see [BootstrapService.ensureUserProvisioned].
     * proot's `-0` flag still fakes uid 0 for the whole container (required
     * for apt/useradd/mount during provisioning), but `runuser -l` inside
     * that faked-root container works exactly like it would on a real
     * Debian host, so code-server and any terminal it spawns end up
     * genuinely running as [defaultUser], with root only reachable via sudo.
     */
    val defaultUser: String get() = "coder"
    val defaultUserHome: String get() = "/home/$defaultUser"

    val isRootfsInstalled: Boolean
        get() = File(rootfsDir, "usr/bin/env").exists()

    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val prootBinary: File get() = File(nativeLibDir, "libproot.so")
    private val prootLoader: File get() = File(nativeLibDir, "libproot-loader.so")
    private val prootLoader32: File get() = File(nativeLibDir, "libproot-loader32.so")

    fun extractRootfsTarget(): File = rootfsDir.apply { mkdirs() }

    /**
     * Maps an absolute POSIX path *as seen from inside the rootfs* (e.g.
     * "/home/coder/workspace") to the real Android [File] backing it. Since
     * [rootfsDir] is just a plain directory under the app's own private
     * storage (proot ptrace-redirects paths at the process level, it does
     * not actually chroot/bind-mount anything), our own process can read
     * and write those files directly with plain java.io -- no need to shell
     * out through proot for simple file copies. Used by SafActions for the
     * SAF folder-workspace sync and single-file import/export features.
     */
    fun resolveInRootfs(posixPath: String): File = File(rootfsDir, posixPath.removePrefix("/"))

    /** Real Android [File] for [defaultUserHome] (typically where SAF-imported/exported files live). */
    fun defaultUserHomeDir(): File = resolveInRootfs(defaultUserHome)

    /**
     * Runs [command] (e.g. `listOf("bash", "-lc", "apt-get install -y code-server")`)
     * inside the proot rootfs as root, blocking until it exits, and returns
     * the process exit code. stdout/stderr are streamed to Logcat under the
     * "CoDebianProot" tag so `adb logcat` shows real bootstrap progress.
     *
     * Pass [runAsUser] (e.g. [defaultUser]) to instead run [command] as that
     * regular user via `runuser -l`, still inside the same faked-root proot
     * container -- required by [command] being exactly `listOf("bash", "-lc", script)`.
     */
    fun runInRootfs(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        runAsUser: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Int {
        val process = buildProcess(command, extraEnv, runAsUser, extraBinds)
        process.inputStream.bufferedReader().forEachLine { Log.i("CoDebianProot", it) }
        return process.waitFor()
    }

    /**
     * Like [runInRootfs] but does not block: used to launch long-running
     * daemons (code-server, sshd, mcp-proxy). Caller owns the
     * returned [Process] and should `destroy()` it when the service stops.
     *
     * [extraBinds] are additional `host:guest` path pairs (real Android
     * paths, e.g. from [ExposureActions]) proot-bound on top of the
     * always-present /dev,/proc,/sys -- same ptrace-level path-translation
     * mechanism, not a kernel mount, but it works the same way regardless of
     * any chroot() a guest process later performs, since proot's bind table
     * is consulted independently of the guest's own idea of its root.
     */
    fun startProcessInRootfs(
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
        runAsUser: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Process {
        val process = buildProcess(command, extraEnv, runAsUser, extraBinds)
        Thread({
            // When the caller later destroy()s this process (e.g. restarting
            // sshd/code-server after a settings change), the blocking read
            // below throws IOException/InterruptedIOException as its pipe is
            // torn down. That's expected/benign here, but left uncaught on a
            // plain Thread it would otherwise crash the whole app process --
            // so swallow it instead of letting it propagate.
            try {
                process.inputStream.bufferedReader().forEachLine { Log.i("CoDebianCodeServer", it) }
            } catch (_: java.io.IOException) {
                // Expected when the process is intentionally destroyed while this thread is blocked on read().
            }
        }, "codeserver-log-pump").apply { isDaemon = true; start() }
        return process
    }

    private fun buildProcess(
        command: List<String>,
        extraEnv: Map<String, String>,
        runAsUser: String? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): Process {
        check(prootBinary.exists()) {
            "proot binary missing at ${prootBinary.absolutePath} -- run scripts/fetch-assets.ps1 first"
        }
        val prootArgs = mutableListOf(
            prootBinary.absolutePath,
            "-0",                       // fake root inside the rootfs
            "--link2symlink",           // (short alias is "-l", lowercase --
                                         // NOT "-L", which is a different
                                         // proot flag for lstat size
                                         // correction). Many Android
                                         // filesystems don't support hardlinks
                                         // via proot's default ptrace path --
                                         // dpkg (and other tools) create
                                         // backup files via link(), which
                                         // fails with EPERM without this
                                         // (known Termux/proot fix for
                                         // Android specifically).
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
        )
        for ((host, guest) in extraBinds) prootArgs += listOf("-b", "$host:$guest")
        prootArgs += listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        prootArgs += if (runAsUser != null) {
            require(command.size == 3 && command[0] == "bash" && command[1] == "-lc") {
                "runAsUser requires command to be exactly listOf(\"bash\", \"-lc\", script), got $command"
            }
            // `runuser -l` logs in as [runAsUser] (resetting HOME/PWD to that
            // user's own, e.g. /home/coder) from within the still-faked-root
            // proot container -- this is the same trick proot-distro uses to
            // offer a non-root default user on top of proot's own "-0".
            listOf("runuser", "-l", runAsUser, "-c", command[2])
        } else {
            command
        }

        val builder = ProcessBuilder(prootArgs)
            .redirectErrorStream(true)
            .directory(tmpDir)
        val env = builder.environment()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        // A bare ProcessBuilder exec of a foreign ELF binary is not a
        // System.loadLibrary() call, so Android does not implicitly add our
        // nativeLibraryDir to the dynamic linker's search path -- proot's
        // own shared-library dependencies (e.g. libtalloc.so, bundled
        // alongside it precisely because of this) need it set explicitly.
        env["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        if (prootLoader.exists()) env["PROOT_LOADER"] = prootLoader.absolutePath
        if (prootLoader32.exists()) env["PROOT_LOADER_32"] = prootLoader32.absolutePath
        env.putAll(extraEnv)

        return builder.start()
    }
}
