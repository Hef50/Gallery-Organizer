package com.galleryorganizer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galleryorganizer.data.db.dao.BucketSummary
import com.galleryorganizer.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Per-folder hiding.
 *
 * Hiding a folder is a *view* preference, not a filter: it never becomes part of a saved
 * search, and asking for a folder explicitly still shows it. Nothing is deleted or
 * untagged — the items are simply kept out of the default grid, which is what people
 * actually want for WhatsApp, Telegram and screenshot folders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    buckets: List<BucketSummary>,
    hiddenIds: Flow<Set<Long>>,
    settings: SettingsStore,
    onBack: () -> Unit,
) {
    val hidden by hiddenIds.collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Turn a folder off to keep it out of the gallery. Nothing is deleted, tags " +
                    "are kept, and searching for the folder still finds it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn {
                items(buckets, key = { it.bucketId }) { bucket ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                bucket.bucketName.ifBlank { "(unnamed folder)" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "%,d items".format(bucket.itemCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = bucket.bucketId !in hidden,
                            onCheckedChange = { visible ->
                                scope.launch {
                                    settings.setBucketHidden(bucket.bucketId, !visible)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
