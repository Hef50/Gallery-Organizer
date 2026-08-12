package com.galleryorganizer.ui.grid

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galleryorganizer.domain.search.SearchQuery

@Composable
fun QuickFilterRow(
    query: SearchQuery,
    onSelect: (QuickFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter.matches(query),
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}
