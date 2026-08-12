package com.galleryorganizer.ui.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Drives "write my tags into my photos".
 *
 * Modifying a file the app does not own needs the user's explicit, per-batch consent via
 * `MediaStore.createWriteRequest`, which puts a system dialog in front of them listing the
 * affected items. That is deliberately not something the app can route around, and it is
 * why write-back is a manual action rather than something that fires on every tag edit.
 */
@Composable
fun XmpExportFlow(
    state: XmpExportState,
    onConsentGranted: () -> Unit,
    onConsentDenied: () -> Unit,
    onSidecarFolderChosen: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onConsentGranted() else onConsentDenied()
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist the grant so the next export does not ask again.
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            onSidecarFolderChosen(uri)
        }
    }

    when (state) {
        is XmpExportState.NeedsSidecarFolder -> ConfirmDialog(
            title = "Where should sidecar files go?",
            body = "A .xmp sidecar sits alongside your photo and never modifies it. " +
                "Android will not let an app create one next to your originals without " +
                "you pointing at the folder first — pick your DCIM folder to keep them " +
                "together.",
            confirmLabel = "Choose folder",
            onConfirm = { folderLauncher.launch(null) },
            onDismiss = onDismiss,
        )

        is XmpExportState.NeedsWriteConsent -> {
            val request = remember(state.uris) { createWriteRequest(context, state.uris) }
            if (request == null) {
                ConfirmDialog(
                    title = "Cannot write to those files",
                    body = "Android did not offer a way to ask for permission to modify them.",
                    confirmLabel = "Close",
                    onConfirm = onDismiss,
                    onDismiss = onDismiss,
                )
            } else {
                ConfirmDialog(
                    title = "Write tags into %,d files?".format(state.uris.size),
                    body = "Each file is copied, rewritten and checked before anything is " +
                        "replaced — if a rewrite does not verify, that file is left exactly " +
                        "as it was. Android will ask you to confirm next.",
                    confirmLabel = "Continue",
                    onConfirm = { consentLauncher.launch(IntentSenderRequest.Builder(request).build()) },
                    onDismiss = onDismiss,
                )
            }
        }

        is XmpExportState.Finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Tags written") },
            text = {
                Text(
                    buildString {
                        append("%,d files updated in place.\n".format(state.report.embedded))
                        if (state.report.sidecars > 0) {
                            append("%,d sidecar files written.\n".format(state.report.sidecars))
                        }
                        if (state.report.skipped > 0) {
                            append("%,d skipped.\n".format(state.report.skipped))
                        }
                        if (state.report.failed > 0) {
                            append("\n%,d could not be written and were left untouched:\n"
                                .format(state.report.failed))
                            state.report.failures.take(5).forEach { append("• $it\n") }
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )

        XmpExportState.Idle -> Unit
        is XmpExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Writing tags") },
            text = { Text("%,d of %,d".format(state.done, state.total)) },
            confirmButton = {},
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * `MediaStore.createWriteRequest` exists from API 30. minSdk is 33, so this is always
 * available; the guard is here only so the intent is obvious to a future reader.
 */
private fun createWriteRequest(context: Context, uris: List<Uri>): android.content.IntentSender? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
        MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
    } else {
        null
    }

sealed interface XmpExportState {
    data object Idle : XmpExportState

    /** The user has to point at a folder before sidecars can be written. */
    data class NeedsSidecarFolder(val pending: List<Long>) : XmpExportState

    /** Android must ask before the app may modify files it does not own. */
    data class NeedsWriteConsent(val uris: List<Uri>, val pending: List<Long>) : XmpExportState

    data class Running(val done: Int, val total: Int) : XmpExportState

    data class Finished(val report: com.galleryorganizer.xmp.XmpExportReport) : XmpExportState
}
