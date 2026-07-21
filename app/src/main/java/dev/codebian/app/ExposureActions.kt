package dev.codebian.app

import android.content.Context
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.PathMatcher

/**
 * Builds the single curated "exposed" filesystem view that the MCP
 * filesystem server serves, from the shared
 * exposure toggles in [AppPreferences] (see the Settings dialog's "File
 * Exposure" section). Reused so the server doesn't need its own
 * separate include/exclude/folder configuration, per the original backlog
 * ask.
 *
 * Three independently toggle-able sources:
 *  - "home": the rootfs home directory (~) -- real, always live.
 *  - "workspace": the SAF-linked ~/workspace mirror (see [SafActions]) --
 *    real, but only as fresh as the last manual sync, since a SAF grant can
 *    never yield a raw file descriptor for a live bind (scoped storage
 *    enforces its ACLs at the FUSE/kernel level, not just the Java API
 *    layer -- confirmed while investigating whether Termux's Play Store
 *    build could inform an alternative; it can't, see its own targetSdk-28
 *    workaround, not applicable to a freshly-submitted app).
 *  - "shared": a folder under the app's own external-files directory
 *    (Android/data/dev.codebian.app/files/shared) -- needs zero permission
 *    (scoped storage exempts app-owned external dirs) and unlike the SAF
 *    workspace can be made genuinely live via a proot bind.
 *
 * All three are exposed to MCP as proot binds under one flat guest
 * root, /mnt/codebian-exposure/{home,workspace,shared} -- never symlinks --
 * specifically because confining the served content to only the curated
 * directory tree calls for a real chroot()-style boundary, and an
 * absolute-path symlink pointing outside the new root would not resolve the
 * way we want once chrooted (proot's own path-translation stack tracks the
 * guest's chroot the same way a real kernel chroot would). A direct proot
 * bind, by contrast, is proot's own translation-table entry, applied
 * independently of whatever the guest process does with chroot() --
 * confirmed by this already being exactly how /dev,/proc,/sys keep working
 * for any guest program that chroots.
 *
 * Include/exclude glob patterns (only meaningful for "home"/"workspace",
 * see [AppPreferences.getExposureIncludePatterns]) are applied by
 * materializing a filtered copy of the directory *tree* (real mkdir at each
 * level) with only the matching files **hard-linked** in -- not
 * symlinked, for the same chroot-safety reason above: a hard link has no
 * path indirection at all, it is just another directory entry for the same
 * underlying inode, so it is completely unaffected by any later chroot/bind
 * reinterpretation. Hard links require the same filesystem, which holds
 * here since the filtered copy lives alongside the rootfs itself. "shared"
 * lives on a different filesystem (external storage) so it is always
 * exposed in full when enabled, never glob-filtered (documented in the
 * Settings caption).
 *
 * Materialization only happens once per explicit "Refresh Exposure" tap (or
 * automatically the first time MCP is enabled) -- matches the existing
 * manual SAF-workspace-sync UX pattern rather than a background
 * file-watcher service.
 */
object ExposureActions {
    private const val GUEST_EXPOSURE_ROOT = "/mnt/codebian-exposure"
    private const val SHARED_GUEST_SUBDIR = "shared"
    private const val FILTERED_CACHE_SUBDIR = "var/lib/codebian-filtered" // rootfs-relative

    /** Real Android File for the app-owned external "shared" folder (no SAF/permission needed). Creates it if missing. */
    fun sharedFolderHostDir(context: Context): File =
        (context.getExternalFilesDir(SHARED_GUEST_SUBDIR) ?: File(context.filesDir, SHARED_GUEST_SUBDIR)).apply { mkdirs() }

    /**
     * `-b host:guest` pairs to pass to [ProotRuntime.startProcessInRootfs]
     * for the MCP server process (code-server
     * and sshd don't need the curated root, they already see the real `~`
     * directly). Callers should re-fetch this (and restart the affected
     * server) whenever the exposure toggles/patterns change, since proot
     * binds are fixed for the lifetime of one process.
     */
    fun exposureBindsFor(context: Context): List<Pair<String, String>> {
        val proot = ProotRuntime(context)
        val binds = mutableListOf<Pair<String, String>>()
        val hasPatterns = hasPatterns(context)

        if (AppPreferences.isExposureHomeEnabled(context)) {
            val src = if (hasPatterns) materializeFiltered(context, "home", proot.defaultUserHomeDir()) else proot.defaultUserHomeDir()
            binds += src.absolutePath to "$GUEST_EXPOSURE_ROOT/home"
        }
        if (AppPreferences.isExposureWorkspaceEnabled(context)) {
            val workspace = File(proot.defaultUserHomeDir(), "workspace")
            if (workspace.exists()) {
                val src = if (hasPatterns) materializeFiltered(context, "workspace", workspace) else workspace
                binds += src.absolutePath to "$GUEST_EXPOSURE_ROOT/workspace"
            }
        }
        if (AppPreferences.isExposureSharedEnabled(context)) {
            binds += sharedFolderHostDir(context).absolutePath to "$GUEST_EXPOSURE_ROOT/$SHARED_GUEST_SUBDIR"
        }
        return binds
    }

    /** Whether at least one exposure source is currently enabled (MCP has nothing to serve otherwise). */
    fun hasAnyExposure(context: Context): Boolean =
        AppPreferences.isExposureHomeEnabled(context) ||
            AppPreferences.isExposureWorkspaceEnabled(context) ||
            AppPreferences.isExposureSharedEnabled(context)

    /** Guest-side root the MCP filesystem server's single allowed dir should point at. */
    const val EXPOSURE_ROOT_GUEST_PATH = GUEST_EXPOSURE_ROOT

    /**
     * Rebuilds the filtered hard-link caches for "home"/"workspace" (if
     * patterns are set) right now, synchronously -- called by the Settings
     * dialog's "Refresh Exposure" button. A no-op (and near-instant) when no
     * patterns are configured, since that case binds the real directories
     * directly with no materialization step.
     */
    fun refreshNow(context: Context) {
        val proot = ProotRuntime(context)
        if (!hasPatterns(context)) return
        if (AppPreferences.isExposureHomeEnabled(context)) materializeFiltered(context, "home", proot.defaultUserHomeDir())
        if (AppPreferences.isExposureWorkspaceEnabled(context)) {
            val workspace = File(proot.defaultUserHomeDir(), "workspace")
            if (workspace.exists()) materializeFiltered(context, "workspace", workspace)
        }
    }

    private fun hasPatterns(context: Context): Boolean =
        AppPreferences.getExposureIncludePatterns(context).isNotBlank() ||
            AppPreferences.getExposureExcludePatterns(context).isNotBlank()

    private fun materializeFiltered(context: Context, name: String, srcRoot: File): File {
        val proot = ProotRuntime(context)
        val dest = proot.resolveInRootfs("$FILTERED_CACHE_SUBDIR/$name")
        dest.deleteRecursively()
        dest.mkdirs()
        val includes = globMatchers(AppPreferences.getExposureIncludePatterns(context))
        val excludes = globMatchers(AppPreferences.getExposureExcludePatterns(context))
        linkFiltered(srcRoot, dest, srcRoot, includes, excludes, depth = 0)
        return dest
    }

    private fun globMatchers(patterns: String): List<PathMatcher> =
        patterns.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
            .toList()

    private fun matches(matchers: List<PathMatcher>, relativePath: String): Boolean =
        matchers.any { it.matches(File(relativePath).toPath()) }

    /** Walks [entry] under [srcRoot], hard-linking (never copying/symlinking) every file that passes the include/exclude glob filters into the matching path under [destDir]. */
    private fun linkFiltered(
        srcRoot: File,
        destDir: File,
        entry: File,
        includes: List<PathMatcher>,
        excludes: List<PathMatcher>,
        depth: Int,
    ) {
        if (depth > 16) return // pathological/circular-symlink guard
        val children = entry.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            val relative = child.relativeTo(srcRoot).path
            if (excludes.isNotEmpty() && matches(excludes, relative)) continue
            if (child.isDirectory) {
                val childDest = File(destDir, child.name).apply { mkdirs() }
                linkFiltered(srcRoot, childDest, child, includes, excludes, depth + 1)
            } else if (includes.isEmpty() || matches(includes, relative)) {
                val destFile = File(destDir, child.name)
                runCatching { Files.createLink(destFile.toPath(), child.toPath()) }
            }
        }
    }
}
