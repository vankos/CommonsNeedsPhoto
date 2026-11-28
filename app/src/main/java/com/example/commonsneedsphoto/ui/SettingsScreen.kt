package com.example.commonsneedsphoto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.store.IgnoredPlace

/**
 * The settings page, opened from the gear in the map top bar. Its only setting is
 * the list of excluded places; opening it shows every excluded point with a cross
 * that puts the point back on the map.
 */
@Composable
fun SettingsScreen(
    ignoredPlaces: List<IgnoredPlace>,
    onRestore: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showIgnored by rememberSaveable { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            // Back from the excluded list returns to settings instead of closing both.
            if (showIgnored) {
                showIgnored = false
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                SettingsHeader(
                    title = stringResource(
                        if (showIgnored) R.string.settings_ignored_places else R.string.settings,
                    ),
                    onBack = if (showIgnored) {
                        { showIgnored = false }
                    } else {
                        onDismiss
                    },
                )
                if (showIgnored) {
                    IgnoredPlacesContent(
                        places = ignoredPlaces,
                        onRestore = onRestore,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SettingsContent(
                        ignoredPlaces = ignoredPlaces,
                        onOpenIgnored = { showIgnored = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsContent(
    ignoredPlaces: List<IgnoredPlace>,
    onOpenIgnored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenIgnored),
            headlineContent = { Text(stringResource(R.string.settings_ignored_places)) },
            supportingContent = {
                Text(
                    if (ignoredPlaces.isEmpty()) {
                        stringResource(R.string.settings_ignored_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_ignored_count,
                            ignoredPlaces.size,
                            ignoredPlaces.size,
                        )
                    },
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.settings_ignored_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun IgnoredPlacesContent(
    places: List<IgnoredPlace>,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (places.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.settings_ignored_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(places, key = { it.qid }) { place ->
            ListItem(
                headlineContent = { Text(place.label) },
                supportingContent = { Text(place.qid) },
                trailingContent = {
                    IconButton(onClick = { onRestore(place.qid) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.settings_restore_place),
                        )
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
