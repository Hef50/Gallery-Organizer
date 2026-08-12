package com.galleryorganizer.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.galleryorganizer.data.prefs.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenDuplicates: () -> Unit = {},
) {
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val itemCount by viewModel.itemCount.collectAsStateWithLifecycle()
    val missingCount by viewModel.missingCount.collectAsStateWithLifecycle()
    val xmpWriteBack by viewModel.settings.xmpWriteBackEnabled.collectAsStateWithLifecycle(false)
    val xmpSidecar by viewModel.settings.xmpSidecarEnabled.collectAsStateWithLifecycle(false)
    val autoTag by viewModel.settings.autoTagEnabled.collectAsStateWithLifecycle(false)
    val confidence by viewModel.settings.autoTagConfidence
        .collectAsStateWithLifecycle(SettingsStore.DEFAULT_AUTO_TAG_CONFIDENCE)

    var confirmingForget by remember { mutableStateOf(false) }
    var confirmingRebuild by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        // Backups are .jsonl; some file pickers only offer */* for unknown extensions, so
        // the filter is deliberately loose rather than helpfully hiding the user's backup.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Your tags")
            Text(
                "Tags live only on this phone. Export them somewhere safe — there is no " +
                    "cloud copy, and a factory reset takes them with it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))

            SettingRow(
                title = "Export tags to a file",
                subtitle = "Tag hierarchy, every tag on every item, OCR text and saved searches",
                onClick = { exportLauncher.launch(defaultBackupName()) },
            )
            SettingRow(
                title = "Restore tags from a file",
                subtitle = "Matches by file contents, so tags find their photos again even " +
                    "after a move or a reinstall. Nothing existing is deleted.",
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )

            if (transfer is TransferState.Running) {
                val running = transfer as TransferState.Running
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (running.exporting) {
                            if (running.total > 0) {
                                "Exporting %,d of %,d items".format(running.progress, running.total)
                            } else {
                                "Exporting %,d items".format(running.progress)
                            }
                        } else {
                            "Restoring %,d items".format(running.progress)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Library")
            InfoRow("Indexed items", "%,d".format(itemCount))
            if (missingCount > 0) {
                InfoRow("Items whose file is gone", "%,d".format(missingCount))
                SettingRow(
                    title = "Forget missing items",
                    subtitle = "Removes database rows for files that are no longer on the " +
                        "phone, along with their tags. Your photos are never touched.",
                    onClick = { confirmingForget = true },
                )
            }
            SettingRow(
                title = "Find duplicates",
                subtitle = "Items whose contents are identical",
                onClick = onOpenDuplicates,
            )
            SettingRow(
                title = "Re-read the whole library",
                subtitle = "Rebuilds the index from scratch. Tags are kept.",
                onClick = { confirmingRebuild = true },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Write tags into files (advanced)")
            Text(
                "Off by default. When on, exporting tags writes them into the photo itself " +
                    "so other apps can read them. Files are written to a temporary copy and " +
                    "verified before anything is replaced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ToggleRow(
                title = "Allow writing into original files",
                checked = xmpWriteBack,
                onCheckedChange = { scope.launch { viewModel.settings.setXmpWriteBackEnabled(it) } },
            )
            ToggleRow(
                title = "Write .xmp sidecar files",
                subtitle = "Never modifies the original — safe for videos and RAW",
                checked = xmpSidecar,
                onCheckedChange = { scope.launch { viewModel.settings.setXmpSidecarEnabled(it) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Suggestions")
            ToggleRow(
                title = "Suggest tags automatically",
                subtitle = "Runs on-device while charging. Suggestions are never applied " +
                    "until you accept them. Currently at %.0f%% confidence."
                        .format(confidence * 100),
                checked = autoTag,
                onCheckedChange = { scope.launch { viewModel.settings.setAutoTagEnabled(it) } },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    when (val state = transfer) {
        is TransferState.Exported -> AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text("Backup saved") },
            text = {
                Text(
                    "%,d tags, %,d tagged items and %,d saved searches.".format(
                        state.stats.tags,
                        state.stats.items,
                        state.stats.savedSearches,
                    ) + "\n\nKeep a copy somewhere other than this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTransfer) { Text("Done") }
            },
        )

        is TransferState.Imported -> AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text(if (state.report.wrongFormat) "That is not a backup" else "Tags restored") },
            text = { Text(state.report.describe()) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTransfer) { Text("Done") }
            },
        )

        is TransferState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissTransfer,
            title = { Text("That didn't work") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTransfer) { Text("Close") }
            },
        )

        else -> Unit
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("Forget %,d missing items?".format(missingCount)) },
            text = {
                Text(
                    "Their tags go with them, and this cannot be undone. If the files might " +
                        "come back — an SD card, a restored folder — leave them alone and " +
                        "their tags will reattach automatically.\n\n" +
                        "No photos or videos are deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetMissing { }
                        confirmingForget = false
                    },
                ) { Text("Forget them") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmingRebuild) {
        AlertDialog(
            onDismissRequest = { confirmingRebuild = false },
            title = { Text("Re-read the whole library?") },
            text = {
                Text(
                    "This reads every photo and video again, which takes a few minutes for a " +
                        "large library. Your tags are kept — they are matched back by file " +
                        "contents, not by position.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.rebuildIndex(); confirmingRebuild = false },
                ) { Text("Re-read") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRebuild = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun defaultBackupName(): String =
    "gallery-organizer-tags-${java.time.LocalDate.now()}.jsonl"

internal fun com.galleryorganizer.data.backup.ImportReport.describe(): String = when {
    wrongFormat -> "That file isn't a Gallery Organizer backup."
    itemsMatched == 0 && itemsUnmatched > 0 ->
        ("None of the %,d items in that backup could be matched to photos on this phone. " +
            "If the library is still being indexed, wait for that to finish and try again.")
            .format(itemsUnmatched)

    else -> buildString {
        append("%,d tags restored (%,d already existed).\n".format(tagsCreated, tagsMerged))
        append("%,d items matched — %,d by contents, %,d by name and size.\n"
            .format(itemsMatched, itemsMatchedByHash, itemsMatchedByName))
        append("%,d tags applied.".format(assignmentsApplied))
        if (ocrRestored > 0) append("\n%,d items got their scanned text back.".format(ocrRestored))
        if (savedSearchesImported > 0) {
            append("\n%,d saved searches added".format(savedSearchesImported))
            if (savedSearchesSkipped > 0) {
                append(
                    " (%,d skipped — a search with that name already exists)"
                        .format(savedSearchesSkipped),
                )
            }
            append(".")
        }
        if (itemsUnmatched > 0) {
            append(
                ("\n\n%,d items in the backup are not on this phone. Their tags are waiting " +
                    "in the file — restore again after those photos come back.")
                    .format(itemsUnmatched),
            )
        }
        if (malformedLines > 0) {
            append("\n\n%,d damaged lines were skipped.".format(malformedLines))
        }
    }
}
