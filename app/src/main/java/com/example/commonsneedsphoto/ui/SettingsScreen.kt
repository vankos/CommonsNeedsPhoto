package com.example.commonsneedsphoto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.store.IgnoredPlace

/** The page of [SettingsScreen] that is shown; back returns to the main page. */
private enum class SettingsPage { MAIN, EXCLUDED_PLACES, EXCLUSION_CATEGORIES }

/**
 * The settings page, opened from the gear in the map top bar. It has two lists:
 * the excluded places, with a cross that puts a point back on the map, and the
 * exclusion categories, whose places are skipped when the map is drawn.
 */
@Composable
fun SettingsScreen(
    ignoredPlaces: List<IgnoredPlace>,
    exclusionCategories: List<String>,
    onRestore: (String) -> Unit,
    onAddExclusionCategory: (String) -> Unit,
    onRemoveExclusionCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }

    Dialog(
        onDismissRequest = {
            // Back from a list returns to settings instead of closing both.
            if (page != SettingsPage.MAIN) {
                page = SettingsPage.MAIN
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
                        when (page) {
                            SettingsPage.MAIN -> R.string.settings
                            SettingsPage.EXCLUDED_PLACES -> R.string.settings_ignored_places
                            SettingsPage.EXCLUSION_CATEGORIES -> R.string.settings_exclusion_categories
                        },
                    ),
                    onBack = if (page != SettingsPage.MAIN) {
                        { page = SettingsPage.MAIN }
                    } else {
                        onDismiss
                    },
                )
                when (page) {
                    SettingsPage.MAIN -> SettingsContent(
                        ignoredPlaces = ignoredPlaces,
                        exclusionCategories = exclusionCategories,
                        onOpenIgnored = { page = SettingsPage.EXCLUDED_PLACES },
                        onOpenCategories = { page = SettingsPage.EXCLUSION_CATEGORIES },
                        modifier = Modifier.weight(1f),
                    )

                    SettingsPage.EXCLUDED_PLACES -> IgnoredPlacesContent(
                        places = ignoredPlaces,
                        onRestore = onRestore,
                        modifier = Modifier.weight(1f),
                    )

                    SettingsPage.EXCLUSION_CATEGORIES -> ExclusionCategoriesContent(
                        categories = exclusionCategories,
                        onAdd = onAddExclusionCategory,
                        onRemove = onRemoveExclusionCategory,
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
    exclusionCategories: List<String>,
    onOpenIgnored: () -> Unit,
    onOpenCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
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
        HorizontalDivider()

        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCategories),
            headlineContent = { Text(stringResource(R.string.settings_exclusion_categories)) },
            supportingContent = {
                Text(
                    pluralStringResource(
                        R.plurals.settings_exclusion_categories_count,
                        exclusionCategories.size,
                        exclusionCategories.size,
                    ),
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
            text = stringResource(R.string.settings_exclusion_categories_hint),
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

/**
 * The exclusion categories, shown like their Wikidata label but stored lower
 * case. The inline field at the bottom adds one without a separate dialog.
 */
@Composable
private fun ExclusionCategoriesContent(
    categories: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var newCategory by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    fun submit() {
        onAdd(newCategory.text)
        newCategory = TextFieldValue("")
        adding = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.settings_exclusion_categories_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        if (categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_exclusion_categories_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(categories, key = { it }) { category ->
                    ListItem(
                        headlineContent = { Text(category.replaceFirstChar { it.titlecase() }) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(category) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(
                                        R.string.settings_exclusion_category_remove,
                                    ),
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        if (adding) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text(stringResource(R.string.settings_exclusion_category_new)) },
                    placeholder = {
                        Text(stringResource(R.string.settings_exclusion_category_placeholder))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    newCategory = TextFieldValue("")
                    adding = false
                }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel))
                }
                IconButton(onClick = { submit() }) {
                    Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.done))
                }
            }
        } else {
            TextButton(
                onClick = { adding = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.settings_exclusion_category_add),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
