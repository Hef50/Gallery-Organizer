package com.galleryorganizer.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galleryorganizer.data.backup.BackupStats
import com.galleryorganizer.data.backup.ImportReport
import com.galleryorganizer.di.AppContainer
import com.galleryorganizer.xmp.XmpContainer
import com.galleryorganizer.xmp.XmpExportReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a backup or restore has got to, for the button that started it. */
sealed interface TransferState {
    data object Idle : TransferState
    data class Running(val progress: Int, val total: Int, val exporting: Boolean) : TransferState
    data class Exported(val stats: BackupStats) : TransferState
    data class Imported(val report: ImportReport) : TransferState
    data class Failed(val message: String) : TransferState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _transfer = MutableStateFlow<TransferState>(TransferState.Idle)
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    val itemCount: StateFlow<Int> = container.mediaRepository.observePresentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val missingCount: StateFlow<Int> = container.mediaRepository.observeMissingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val settings = container.settings

    fun export(target: Uri) {
        _transfer.value = TransferState.Running(0, 0, exporting = true)
        viewModelScope.launch {
            runCatching {
                // "wt" truncates: without it, overwriting a larger existing backup leaves
                // the tail of the old file behind and produces a corrupt one.
                val stream = container.appContext.contentResolver.openOutputStream(target, "wt")
                    ?: error("Could not open the file for writing")
                stream.use { out ->
                    container.backupRepository.export(out) { written, total ->
                        _transfer.value = TransferState.Running(written, total, exporting = true)
                    }
                }
            }.fold(
                onSuccess = { _transfer.value = TransferState.Exported(it) },
                onFailure = { _transfer.value = TransferState.Failed(it.friendlyMessage()) },
            )
        }
    }

    fun import(source: Uri) {
        _transfer.value = TransferState.Running(0, 0, exporting = false)
        viewModelScope.launch {
            runCatching {
                val stream = container.appContext.contentResolver.openInputStream(source)
                    ?: error("Could not open that file")
                stream.use { input ->
                    container.backupRepository.import(input) { read ->
                        _transfer.value = TransferState.Running(read, 0, exporting = false)
                    }
                }
            }.fold(
                onSuccess = { _transfer.value = TransferState.Imported(it) },
                onFailure = { _transfer.value = TransferState.Failed(it.friendlyMessage()) },
            )
        }
    }

    fun dismissTransfer() {
        _transfer.value = TransferState.Idle
    }

    // --- XMP write-back (P8) -----------------------------------------------------------

    private val _xmp = MutableStateFlow<XmpExportState>(XmpExportState.Idle)
    val xmp: StateFlow<XmpExportState> = _xmp.asStateFlow()

    private var sidecarFolder: Uri? = null

    /**
     * Starts a write-back run. Embedding into files the app does not own requires the
     * user's explicit consent through a system dialog, so the flow pauses here and the UI
     * fires `MediaStore.createWriteRequest` before anything is written.
     */
    fun beginXmpExport() {
        viewModelScope.launch {
            val embed = container.settings.xmpWriteBackEnabled.first()
            val sidecars = container.settings.xmpSidecarEnabled.first()
            if (!embed && !sidecars) {
                _xmp.value = XmpExportState.Finished(XmpExportReport())
                return@launch
            }

            val ids = container.database.mediaDao().let { dao ->
                buildList {
                    var offset = 0
                    while (true) {
                        val page = dao.taggedIdsPage(500, offset)
                        if (page.isEmpty()) break
                        addAll(page)
                        offset += page.size
                        if (page.size < 500) break
                    }
                }
            }
            if (ids.isEmpty()) {
                _xmp.value = XmpExportState.Finished(XmpExportReport())
                return@launch
            }

            if (sidecars && sidecarFolder == null) {
                _xmp.value = XmpExportState.NeedsSidecarFolder(ids)
                return@launch
            }
            if (embed) {
                val uris = container.database.mediaDao().byIds(ids)
                    .filter { XmpContainer.forMimeType(it.mime) != XmpContainer.Sidecar }
                    .map { Uri.parse(it.uri) }
                if (uris.isNotEmpty()) {
                    _xmp.value = XmpExportState.NeedsWriteConsent(uris, ids)
                    return@launch
                }
            }
            runXmpExport(ids, allowEmbedding = embed)
        }
    }

    fun onSidecarFolderChosen(uri: Uri) {
        sidecarFolder = uri
        val pending = (_xmp.value as? XmpExportState.NeedsSidecarFolder)?.pending
        _xmp.value = XmpExportState.Idle
        if (pending != null) beginXmpExport()
    }

    fun onWriteConsentGranted() {
        val state = _xmp.value as? XmpExportState.NeedsWriteConsent ?: return
        runXmpExport(state.pending, allowEmbedding = true)
    }

    fun onWriteConsentDenied() {
        // Falling back to sidecars is the useful thing to do: the user said no to changing
        // their originals, not no to exporting tags.
        val state = _xmp.value as? XmpExportState.NeedsWriteConsent ?: return
        if (sidecarFolder != null) {
            runXmpExport(state.pending, allowEmbedding = false)
        } else {
            _xmp.value = XmpExportState.Idle
        }
    }

    fun dismissXmp() {
        _xmp.value = XmpExportState.Idle
    }

    private fun runXmpExport(ids: List<Long>, allowEmbedding: Boolean) {
        _xmp.value = XmpExportState.Running(0, ids.size)
        viewModelScope.launch {
            val report = container.xmpWriteBack.exportTags(
                mediaIds = ids,
                allowEmbedding = allowEmbedding,
                sidecarDirectory = sidecarFolder,
            ) { done, total -> _xmp.value = XmpExportState.Running(done, total) }
            _xmp.value = XmpExportState.Finished(report)
        }
    }

    fun forgetMissing(onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(container.mediaRepository.forgetMissing()) }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            container.mediaIndexer.resetWatermarks()
            com.galleryorganizer.work.WorkScheduler.enqueueIndex(container.appContext, sweepMissing = true)
        }
    }

    private fun Throwable.friendlyMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Something went wrong (${this::class.simpleName})"

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    }
}
