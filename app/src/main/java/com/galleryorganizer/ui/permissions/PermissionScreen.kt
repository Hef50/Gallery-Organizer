package com.galleryorganizer.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galleryorganizer.permissions.MediaPermissions

/**
 * The pre-permission screen. Shown only when nothing at all is granted.
 *
 * @param hasAskedBefore false on a cold install. Combined with [canShowRationale] it
 *   distinguishes "we have never asked" from "denied for good, send them to Settings",
 *   which `shouldShowRequestPermissionRationale` cannot do on its own.
 */
@Composable
fun MediaPermissionScreen(
    hasAskedBefore: Boolean,
    canShowRationale: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanentlyDenied = hasAskedBefore && !canShowRationale

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Organize your gallery",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Gallery Organizer reads the photos and videos already on this phone and " +
                    "lets you tag and search them. It never moves, copies or deletes them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
            PromiseRow(
                Icons.Outlined.CloudOff,
                "Nothing leaves your phone",
                "The app has no internet permission at all. There is no account and no upload.",
            )
            Spacer(Modifier.height(16.dp))
            PromiseRow(
                Icons.Outlined.Sell,
                "Tags live alongside your photos",
                "Your tags are stored in this app and matched to files by content, so they " +
                    "survive moves, reinstalls and re-indexing.",
            )

            Spacer(Modifier.height(36.dp))

            if (permanentlyDenied) {
                Text(
                    "Media access is turned off for this app. You can turn it back on in " +
                        "Android Settings → Permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            } else {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasAskedBefore) "Try again" else "Allow access to photos & videos")
                }
                if (MediaPermissions.supportsPartialGrant) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can also choose \"Select photos and videos\" and share only some " +
                            "of them. Everything still works — the app just sees fewer items.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PromiseRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown above the grid on Android 14+ when the user granted access to a hand-picked
 * subset. Dismissible, and never re-shown once dismissed — see OPEN_QUESTIONS.md #2.
 */
@Composable
fun PartialAccessBanner(
    onManageSelection: () -> Unit,
    onGrantAll: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "You've shared only some photos and videos with this app, so only those " +
                        "can be organized.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onManageSelection) { Text("Change selection") }
                TextButton(onClick = onGrantAll) { Text("Allow all") }
            }
        }
    }
}
