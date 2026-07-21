package dev.codebian.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class BootstrapState {
    data object Idle : BootstrapState()
    data class Downloading(val what: String, val percent: Int) : BootstrapState()
    data object VerifyingDownload : BootstrapState()
    data object ExtractingRootfs : BootstrapState()
    data object InstallingCodeServer : BootstrapState()
    data class InstallingBundledTools(val tool: String) : BootstrapState()
    data object StartingServer : BootstrapState()
    data class Ready(val port: Int) : BootstrapState()
    data object UpdatingCodeServer : BootstrapState()
    /**
     * A brief, non-error interruption while code-server restarts to pick up
     * a changed port/auth-password (see [BootstrapService.syncCodeServerState]).
     * [MainActivity] shows the same kind of overlay as [UpdatingCodeServer]
     * for this state rather than letting the WebView show a raw
     * connection-refused page during the gap.
     */
    data object RestartingCodeServer : BootstrapState()
    data class Error(val message: String) : BootstrapState()
}

/**
 * Identifiers for [BootstrapState.InstallingBundledTools.tool], shared
 * between [BootstrapService] (which reports them) and [MainActivity]
 * (which renders a step-by-step checklist from them) so the two never
 * drift out of sync on the string values used.
 */
object BootstrapTool {
    const val GIT = "git"
    const val NODEJS = "nodejs"
    const val PYTHON = "python"
}

/**
 * Process-wide bootstrap status, published by [BootstrapService] and
 * observed by [MainActivity] to decide when to point the WebView at
 * code-server. A simple singleton is enough here: there is only ever one
 * rootfs/one code-server instance per app install.
 */
object BootstrapManager {
    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val state: StateFlow<BootstrapState> = _state

    fun update(newState: BootstrapState) {
        _state.value = newState
    }
}
