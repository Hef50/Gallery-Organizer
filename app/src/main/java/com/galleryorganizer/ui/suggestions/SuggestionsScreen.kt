package com.galleryorganizer.ui.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.dao.SuggestionGroup

/**
 * The review queue.
 *
 * Grouped by label, not by photo: "Beach — 340 photos" is one decision, where reviewing
 * item by item would be 340. Accepting writes real tags with `source = auto`; rejecting
 * keeps the rows marked rejected so the same guess is never offered again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(viewModel: SuggestionsViewModel, onBack: () -> Unit) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val remaining by viewModel.remaining.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle(false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suggested tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!enabled) {
                Text(
                    "Automatic suggestions are turned off. Turn them on in Settings and the " +
                        "app will look through your photos while the phone is charging.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp)) {
                    Button(onClick = viewModel::enableAndStart) { Text("Turn on and start") }
                }
            } else if (remaining > 0) {
                Text(
                    "%,d photos still to look at. This happens while the phone is charging " +
                        "and idle, so it can take a few nights for a large library."
                        .format(remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (groups.isEmpty()) {
                EmptySuggestions(enabled)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(groups, key = { it.label }) { group ->
                        SuggestionRow(
                            group = group,
                            onAccept = { viewModel.accept(group.label) },
                            onReject = { viewModel.reject(group.label) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(group: SuggestionGroup, onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "%,d %s · %.0f%% sure".format(
                    group.itemCount,
                    if (group.itemCount == 1) "photo" else "photos",
                    group.averageConfidence * 100,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onReject) { Text("No") }
        Button(onClick = onAccept) { Text("Tag them") }
    }
}

@Composable
private fun EmptySuggestions(enabled: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("Nothing to review", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (enabled) {
                "Suggestions appear here once the phone has had a chance to look through " +
                    "your photos while charging."
            } else {
                "Turn on automatic suggestions to have the app propose tags."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
