package com.example.commonsneedsphoto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.upload.UploadLicense

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSheet(
    state: UploadUiState,
    userName: String?,
    origin: LatLon,
    viewModel: MainViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var showPhotoActions by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(viewModel::onPhotoSelected)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        TakePictureWithPermission(),
    ) { success ->
        viewModel.onCameraResult(success)
    }
    // Android 13+ needs POST_NOTIFICATIONS for the WorkManager progress notification.
    // The upload runs even when the permission is denied; only the notification is hidden.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.upload()
    }
    val startUpload = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.upload()
        }
    }
    val canDismiss = !state.isUploading || state.isWaitingForNetwork

    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) viewModel.dismissUpload() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        val uploaded = state.uploaded
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            if (uploaded != null) {
                UploadSuccessContent(
                    state = state,
                    onOpenFile = { uriHandler.openUri(uploaded.pageUrl) },
                    onDone = viewModel::dismissUpload,
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.upload_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            text = state.place.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    val distance = state.place.distanceMeters(origin.latitude, origin.longitude)
                    Text(
                        text = stringResource(R.string.place_distance, formatDistance(distance)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.place_no_photo),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    state.place.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.place.instanceOf?.let { instanceOf ->
                        Text(
                            text = stringResource(R.string.place_instance_of, instanceOf),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { uriHandler.openUri(state.place.wikidataUrl) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(state.place.qid)
                    }
                }
                IconButton(
                    onClick = { viewModel.ignorePlace(state.place) },
                    enabled = !state.isUploading,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ignore),
                        contentDescription = stringResource(R.string.ignore_place),
                    )
                }
                IconButton(
                    onClick = viewModel::dismissUpload,
                    enabled = canDismiss,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            PhotoPicker(
                state = state,
                showPhotoActions = showPhotoActions,
                onToggleActions = { showPhotoActions = !showPhotoActions },
                onEditPhoto = viewModel::editPhoto,
                onTakePhoto = {
                    showPhotoActions = false
                    cameraLauncher.launch(viewModel.newCameraTarget())
                },
                onPickGallery = {
                    showPhotoActions = false
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemove = {
                    showPhotoActions = false
                    viewModel.removePhoto()
                },
            )

            if (state.isPreparingPhoto) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.preparing_photo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.fileName,
                onValueChange = { value ->
                    viewModel.updateUpload { it.copy(fileName = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.file_name)) },
                singleLine = true,
                enabled = !state.isUploading,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.description,
                onValueChange = { value ->
                    viewModel.updateUpload { it.copy(description = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.description)) },
                minLines = 2,
                maxLines = 4,
                enabled = !state.isUploading,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.category,
                onValueChange = { value ->
                    viewModel.updateUpload { it.copy(category = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.category_optional)) },
                singleLine = true,
                enabled = !state.isUploading,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.license_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UploadLicense.entries.forEach { license ->
                    FilterChip(
                        selected = state.license == license,
                        onClick = {
                            viewModel.updateUpload { it.copy(license = license) }
                        },
                        label = { Text(licenseLabel(license)) },
                        enabled = !state.isUploading,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = state.licenseAccepted,
                    onCheckedChange = { checked ->
                        viewModel.updateUpload { it.copy(licenseAccepted = checked) }
                    },
                    enabled = !state.isUploading,
                )
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = stringResource(R.string.license_agreement),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.license_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (userName == null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.login_required),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::showLogin) {
                            Text(stringResource(R.string.login))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    text = stringResource(R.string.logged_in_as, userName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.isUploading) {
                if (state.isWaitingForNetwork) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.upload_waiting_network),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.isPublishing) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.upload_publishing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            Button(
                onClick = startUpload,
                enabled = !state.isUploading && !state.isPreparingPhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = when {
                        state.isUploading && state.isPublishing ->
                            stringResource(R.string.upload_publishing)
                        state.isUploading && state.isWaitingForNetwork ->
                            stringResource(R.string.upload_uploading_indeterminate)
                        state.isUploading ->
                            stringResource(R.string.upload_uploading, state.progress)
                        else -> stringResource(R.string.upload_button)
                    },
                )
            }
        }
    }
}

@Composable
private fun PhotoPicker(
    state: UploadUiState,
    showPhotoActions: Boolean,
    onToggleActions: () -> Unit,
    onEditPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onRemove: () -> Unit,
) {
    if (state.photo != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(
                model = state.photo,
                contentDescription = stringResource(R.string.photo_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                IconButton(
                    onClick = onEditPhoto,
                    enabled = !state.isUploading && !state.isPreparingPhoto,
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.edit_photo),
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showPhotoActions) {
                        IconButton(onClick = onTakePhoto, enabled = !state.isUploading) {
                            Icon(
                                painter = painterResource(R.drawable.ic_camera),
                                contentDescription = stringResource(R.string.take_photo),
                            )
                        }
                        IconButton(onClick = onPickGallery, enabled = !state.isUploading) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gallery),
                                contentDescription = stringResource(R.string.choose_gallery),
                            )
                        }
                        IconButton(onClick = onRemove, enabled = !state.isUploading) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.remove_photo),
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onToggleActions,
                            enabled = !state.isUploading,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Text(stringResource(R.string.replace_photo))
                        }
                    }
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.upload_photo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTakePhoto) {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.take_photo))
                    }
                    OutlinedButton(onClick = onPickGallery) {
                        Icon(
                            painter = painterResource(R.drawable.ic_gallery),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.choose_gallery))
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadSuccessContent(
    state: UploadUiState,
    onOpenFile: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upload_success_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.upload_success_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.uploaded?.let { uploaded ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = uploaded.fileName,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.isUpdatingWikidata -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.wikidata_updating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.wikidataUpdated == true -> {
                Text(
                    text = stringResource(R.string.wikidata_updated),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.wikidataError != null -> {
                Text(
                    text = stringResource(R.string.wikidata_failed, state.wikidataError),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_file_page))
        }
        TextButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isUpdatingWikidata,
        ) {
            Text(stringResource(R.string.done))
        }
    }
}

@Composable
private fun licenseLabel(license: UploadLicense): String = when (license) {
    UploadLicense.CC_BY_SA_4 -> stringResource(R.string.license_cc_by_sa_4)
    UploadLicense.CC_BY_4 -> stringResource(R.string.license_cc_by_4)
    UploadLicense.CC0 -> stringResource(R.string.license_cc0)
}
