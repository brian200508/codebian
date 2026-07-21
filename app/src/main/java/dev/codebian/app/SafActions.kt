package dev.codebian.app

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage Access Framework actions reachable from the floating menu button's
 * "Files" submenu: a folder-workspace mirror (ACTION_OPEN_DOCUMENT_TREE,
 * persisted via takePersistableUriPermission, manually re-synced on demand).
 *
 * Deliberately does NOT use MANAGE_EXTERNAL_STORAGE -- that permission is a
 * very high Play Store rejection risk for an app like this one that doesn't
 * need broad filesystem access as its core purpose (confirmed during this
 * project's Play Store compliance research). SAF is the maximum compliant
 * file-access surface, at the cost of needing an explicit copy step into/out
 * of the rootfs rather than true shared/live storage.
 *
 * All the SAF Uris this class deals with point at *real Android files*, and
 * so does the rootfs itself (see [ProotRuntime.resolveInRootfs]) -- so every
 * copy here is a plain java.io stream copy, never a shell-out through proot.
 *
 * Must be constructed during the host Activity's onCreate() (before
 * onStart()), same as any other registerForActivityResult() caller.
 */
class SafActions(private val activity: AppCompatActivity) {

    private val proot = ProotRuntime(activity)
    private val homeDir: File get() = proot.defaultUserHomeDir()
    private val workspaceDir: File get() = File(homeDir, "workspace")

    private val openDocumentTreeLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) linkWorkspaceFolder(uri)
        }

    /** "Pick Workspace Folder" quick action: choose (or replace) the SAF tree mirrored to `~/workspace`. */
    fun pickWorkspaceFolder() {
        openDocumentTreeLauncher.launch(null)
    }

    /** "Sync Workspace Back" quick action: copies `~/workspace` back out to the previously-picked SAF tree. */
    fun syncWorkspaceBack() {
        val uriString = AppPreferences.getWorkspaceTreeUri(activity)
        if (uriString == null) {
            toast(activity.getString(R.string.saf_sync_no_folder_toast))
            return
        }
        if (!workspaceDir.exists()) {
            toast(activity.getString(R.string.saf_sync_empty_workspace_toast))
            return
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(activity, Uri.parse(uriString))
                    ?: error("workspace folder access lost")
                copyTreeOut(workspaceDir, treeDoc)
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.saf_sync_success_toast))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.saf_sync_failed_toast, e.message ?: e.toString()))
                }
            }
        }
    }

    private fun linkWorkspaceFolder(uri: Uri) {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        AppPreferences.setWorkspaceTreeUri(activity, uri.toString())
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(activity, uri) ?: error("could not open picked folder")
                workspaceDir.mkdirs()
                copyTreeIn(treeDoc, workspaceDir)
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.saf_workspace_linked_toast))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(activity.getString(R.string.saf_workspace_link_failed_toast, e.message ?: e.toString()))
                }
            }
        }
    }

    private fun copyTreeIn(srcDoc: DocumentFile, destDir: File) {
        for (child in srcDoc.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyTreeIn(child, File(destDir, name).apply { mkdirs() })
            } else {
                activity.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun copyTreeOut(srcDir: File, destDoc: DocumentFile) {
        for (child in srcDir.listFiles().orEmpty().sortedBy { it.name }) {
            if (child.isDirectory) {
                val childDoc = destDoc.findFile(child.name) ?: destDoc.createDirectory(child.name) ?: continue
                copyTreeOut(child, childDoc)
            } else {
                // Delete-then-recreate rather than truncate-in-place: SAF's
                // DocumentFile has no "open for overwrite" primitive, and
                // most providers (including local storage) handle
                // create-over-existing-name reliably this way.
                destDoc.findFile(child.name)?.delete()
                val newDoc = destDoc.createFile(guessMimeType(child.name), child.name) ?: continue
                activity.contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                    child.inputStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    /** Best-effort MIME type guess for a file name, used when creating SAF documents during workspace sync-back. */
    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
