package com.galleryorganizer.ui.duplicates

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.ui.grid.MediaCell

/**
 * Items whose bytes are identical, found via `content_hash`.
 *
 * Two things are deliberate here. First, the oldest copy is always kept and never offered
 * for removal — it is the one whose path other things are most likely to reference.
 * Second, "remove" means **move to the system trash**, not delete: Android keeps trashed
 * media recoverable for 30 days, and this app is not going to be the reason a photo is
 * gone forever. Either way Android shows its own confirmation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(viewModel: DuplicatesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<DuplicateGroup?>(null) }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.scanning -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.unhashed > 0 && state.groups.isEmpty() -> NeedsHashing(
                    remaining = state.unhashed,
                    hashing = state.hashing,
                    onStart = viewModel::hashEverything,
                )

                state.groups.isEmpty() -> Empty()

                else -> {
                    if (state.unhashed > 0) {
                        Text(
                            "%,d items have not been checked yet, so there may be more."
                                .format(state.unhashed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn {
                        items(state.groups, key = { it.hash }) { group ->
                            DuplicateGroupRow(group) { confirming = group }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    confirming?.let { group ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Move %,d copies to trash?".format(group.copies.size)) },
            text = {
                Text(
                    "The oldest copy — ${group.original.displayName} — is kept. The others " +
                        "go to Android's trash, where they stay recoverable for 30 days.\n\n" +
                        "Android will ask you to confirm as well. Any tags on the removed " +
                        "copies stay in the app in case the files come back.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestTrash(group) { sender ->
                            trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        confirming = null
                    },
                ) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DuplicateGroupRow(group: DuplicateGroup, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.original.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "%d copies · %s each".format(group.all.size, formatSize(group.original.size)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) { Text("Keep oldest") }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(group.all, key = { it.id }) { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaCell(
                        media = item,
                        selected = false,
                        selectionActive = false,
                        modifier = Modifier.size(96.dp),
                    )
                    Text(
                        if (item.id == group.original.id) "keep" else "duplicate",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.id == group.original.id) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedsHashing(remaining: Int, hashing: Boolean, onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Not everything has been checked", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Finding duplicates means reading a little of every file. The app does this " +
                "gradually in the background — %,d items to go. You can start it now if " +
                "you'd rather not wait.".format(remaining),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (hashing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Button(onClick = onStart) { Text("Check them now") }
        }
    }
}

@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("No duplicates found", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Nothing on this phone has byte-identical copies.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
