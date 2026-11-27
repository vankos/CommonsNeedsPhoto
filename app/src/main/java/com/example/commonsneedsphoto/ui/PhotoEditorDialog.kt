package com.example.commonsneedsphoto.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.photo.AutoEnhance
import com.example.commonsneedsphoto.data.photo.Corner
import com.example.commonsneedsphoto.data.photo.CropRect
import com.example.commonsneedsphoto.data.photo.NormalizedPoint
import com.example.commonsneedsphoto.data.photo.PerspectiveQuad
import com.example.commonsneedsphoto.data.photo.PhotoEditRenderer
import com.example.commonsneedsphoto.data.photo.UprightLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditorMode(@StringRes val label: Int) {
    CROP(R.string.editor_tab_crop),
    GEOMETRY(R.string.editor_tab_geometry),
    COLOR(R.string.editor_tab_color),
}

private enum class AspectOption(@StringRes val label: Int, val ratio: Float?) {
    FREE(R.string.editor_aspect_free, null),
    SQUARE(R.string.editor_aspect_square, 1f),
    FOUR_THREE(R.string.editor_aspect_four_three, 4f / 3f),
    THREE_TWO(R.string.editor_aspect_three_two, 3f / 2f),
    SIXTEEN_NINE(R.string.editor_aspect_sixteen_nine, 16f / 9f),
}

private data class EditorPreview(val bitmap: Bitmap?, val isLoading: Boolean)

private const val DEFAULT_AUTO_STRENGTH = 0.7f

@Composable
fun PhotoEditorDialog(
    state: PhotoEditorUiState,
    onUpdate: ((PhotoEditorUiState) -> PhotoEditorUiState) -> Unit,
    onAutoGeometry: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val previewState by produceState(
        initialValue = EditorPreview(bitmap = null, isLoading = true),
        key1 = state.source,
    ) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { PhotoEditRenderer.decodePreview(state.source) }.getOrNull()
        }
        value = EditorPreview(bitmap = bitmap, isLoading = false)
    }

    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    val mode = EditorMode.entries[modeIndex]
    var aspectIndex by rememberSaveable { mutableIntStateOf(0) }
    val aspect = AspectOption.entries[aspectIndex]
    var geometryPreview by rememberSaveable { mutableStateOf(false) }
    var wasDetecting by remember { mutableStateOf(false) }
    LaunchedEffect(state.isDetecting, state.autoGeometry, state.quad, state.error) {
        if (wasDetecting && !state.isDetecting && state.error == null) {
            geometryPreview = true
        }
        wasDetecting = state.isDetecting
    }

    val rotated = remember(previewState.bitmap, state.rotationDegrees) {
        previewState.bitmap?.let { PhotoEditRenderer.rotate(it, state.rotationDegrees) }
    }
    val uprighted = remember(rotated, state.autoGeometry) {
        rotated?.let { PhotoEditRenderer.upright(it, state.autoGeometry) }
    }
    val uprightedCanvas = remember(rotated, state.autoGeometry) {
        if (state.autoGeometry == null) {
            null
        } else {
            rotated?.let { PhotoEditRenderer.upright(it, state.autoGeometry, keepCanvas = true) }
        }
    }
    val autoLayout = remember(rotated, state.autoGeometry) {
        val image = rotated
        val geometry = state.autoGeometry
        if (image == null || geometry == null) null else geometry.layout(image.width, image.height)
    }
    // While the user drags corners we show the uprighted image with the quad
    // overlay and do not re-warp the preview on every pointer move.
    val rectified = remember(uprighted, state.quad, mode) {
        if (mode == EditorMode.GEOMETRY) {
            uprighted
        } else {
            uprighted?.let { PhotoEditRenderer.warp(it, state.quad) }
        }
    }
    val cropped = remember(rectified, state.crop, mode) {
        if (mode == EditorMode.COLOR) {
            rectified?.let { PhotoEditRenderer.crop(it, state.crop) }
        } else {
            null
        }
    }
    val autoMatrix = remember(cropped ?: rectified) {
        (cropped ?: rectified)?.let { AutoEnhance.matrixFor(it, 1f) }
    }
    val colorFilter = remember(autoMatrix, state.autoEnhance) {
        val matrix = autoMatrix
        if (matrix == null || state.autoEnhance <= 0f) {
            null
        } else {
            ColorFilter.colorMatrix(
                ColorMatrix(AutoEnhance.lerpWithIdentity(matrix, state.autoEnhance)),
            )
        }
    }

    // Auto geometry shows the whole warped frame on white and the rectangle
    // that will be saved. A manual quad switches this preview back to the
    // usual "result of the corners" view.
    val showAutoPreview = mode == EditorMode.GEOMETRY &&
        geometryPreview &&
        state.autoGeometry != null &&
        state.quad == null
    val showQuadPreview = mode == EditorMode.GEOMETRY && geometryPreview && !showAutoPreview
    val warpedPreview = remember(uprighted, state.quad, showQuadPreview) {
        if (showQuadPreview) uprighted?.let { PhotoEditRenderer.warp(it, state.quad) } else null
    }
    val displayed = when {
        showAutoPreview -> uprightedCanvas
        showQuadPreview -> warpedPreview
        mode == EditorMode.CROP -> rectified
        mode == EditorMode.GEOMETRY -> uprighted
        else -> cropped
    }
    val hasEdits = state.rotationDegrees != 0 ||
        state.autoGeometry != null ||
        (state.quad != null && !state.quad.isFullFrame()) ||
        !state.crop.isFull ||
        state.autoEnhance > 0f
    val resetAll = {
        aspectIndex = 0
        geometryPreview = false
        onUpdate {
            it.copy(
                rotationDegrees = 0,
                autoGeometry = null,
                quad = null,
                crop = CropRect.FULL,
                autoEnhance = 0f,
            )
        }
    }
    val selectMode = { selected: EditorMode ->
        if (selected != EditorMode.GEOMETRY) geometryPreview = false
        modeIndex = selected.ordinal
    }

    Dialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val previewHeight = (maxHeight * 0.52f)
                    .coerceAtLeast(160.dp)
                    .coerceAtMost((maxHeight - 240.dp).coerceAtLeast(160.dp))
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !state.isSaving,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                        Text(
                            text = stringResource(R.string.editor_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.isDetecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        if (hasEdits) {
                            IconButton(
                                onClick = resetAll,
                                enabled = !state.isSaving && !state.isDetecting,
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.editor_reset_all),
                                )
                            }
                        }
                        IconButton(
                            onClick = onApply,
                            enabled = !state.isSaving &&
                                !state.isDetecting &&
                                previewState.bitmap != null,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.editor_apply),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                            .background(Color.Black)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        when {
                            previewState.isLoading -> CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = Color.White,
                            )

                            displayed == null -> Text(
                                text = stringResource(R.string.editor_preview_failed),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                            )

                            showAutoPreview -> AutoGeometryPreview(
                                bitmap = displayed,
                                layout = autoLayout,
                            )

                            mode == EditorMode.CROP -> CropPreview(
                                bitmap = displayed,
                                crop = state.crop,
                                aspect = aspect,
                                colorFilter = colorFilter,
                                onCrop = { crop -> onUpdate { it.copy(crop = crop) } },
                            )

                            mode == EditorMode.GEOMETRY && !geometryPreview -> GeometryPreview(
                                bitmap = displayed,
                                quad = state.quad ?: PerspectiveQuad.FULL_FRAME,
                                colorFilter = colorFilter,
                                onQuad = { quad -> onUpdate { it.copy(quad = quad) } },
                            )

                            else -> PlainPreview(
                                bitmap = displayed,
                                colorFilter = colorFilter,
                            )
                        }
                    }

                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }

                    state.notice?.let { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }

                    TabRow(
                        selectedTabIndex = mode.ordinal,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        EditorMode.entries.forEach { entry ->
                            Tab(
                                selected = mode == entry,
                                onClick = { selectMode(entry) },
                                text = { Text(stringResource(entry.label)) },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        when (mode) {
                            EditorMode.CROP -> CropControls(
                                aspectIndex = aspectIndex,
                                onAspect = { index ->
                                    aspectIndex = index
                                    val ratio = AspectOption.entries[index].ratio
                                    val image = rectified
                                    if (ratio != null && image != null) {
                                        onUpdate {
                                            it.copy(
                                                crop = CropRect.centered(
                                                    pixelAspect = ratio,
                                                    imageWidth = image.width,
                                                    imageHeight = image.height,
                                                ),
                                            )
                                        }
                                    }
                                },
                                onRotate = {
                                    aspectIndex = 0
                                    geometryPreview = false
                                    onUpdate {
                                        it.copy(
                                            rotationDegrees = (it.rotationDegrees + 90) % 360,
                                            autoGeometry = null,
                                            quad = null,
                                            crop = CropRect.FULL,
                                        )
                                    }
                                },
                                onReset = {
                                    aspectIndex = 0
                                    onUpdate { it.copy(crop = CropRect.FULL) }
                                },
                                hasCustomCrop = !state.crop.isFull,
                            )

                            EditorMode.GEOMETRY -> GeometryControls(
                                detecting = state.isDetecting,
                                previewingResult = geometryPreview,
                                onAutoGeometry = onAutoGeometry,
                                onTogglePreview = { geometryPreview = !geometryPreview },
                                onReset = {
                                    geometryPreview = false
                                    onUpdate {
                                        it.copy(
                                            autoGeometry = null,
                                            quad = null,
                                        )
                                    }
                                },
                            )

                            EditorMode.COLOR -> ColorControls(
                                strength = state.autoEnhance,
                                onStrength = { strength -> onUpdate { it.copy(autoEnhance = strength) } },
                            )
                        }
                    }
                }

                if (state.isSaving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}

}

@Composable
private fun CropControls(
    aspectIndex: Int,
    onAspect: (Int) -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    hasCustomCrop: Boolean,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AspectOption.entries.forEachIndexed { index, option ->
            FilterChip(
                selected = aspectIndex == index,
                onClick = { onAspect(index) },
                label = { Text(stringResource(option.label)) },
            )
        }
    }
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onRotate) {
            Text(stringResource(R.string.editor_rotate_90))
        }
        TextButton(onClick = onReset, enabled = hasCustomCrop) {
            Text(stringResource(R.string.editor_crop_reset))
        }
    }
    Text(
        text = stringResource(R.string.editor_crop_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GeometryControls(
    detecting: Boolean,
    previewingResult: Boolean,
    onAutoGeometry: () -> Unit,
    onTogglePreview: () -> Unit,
    onReset: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onAutoGeometry,
            enabled = !detecting,
            modifier = Modifier.weight(1f),
        ) {
            if (detecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.editor_auto_geometry))
            }
        }
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = previewingResult,
            onClick = onTogglePreview,
            label = {
                Text(
                    stringResource(
                        if (previewingResult) {
                            R.string.editor_edit_corners
                        } else {
                            R.string.editor_preview_result
                        },
                    ),
                )
            },
        )
    }
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.editor_geometry_reset))
        }
    }
    Text(
        text = stringResource(R.string.editor_geometry_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColorControls(
    strength: Float,
    onStrength: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.editor_auto_color),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.editor_auto_color_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = strength > 0f,
            onCheckedChange = { enabled ->
                onStrength(if (enabled) DEFAULT_AUTO_STRENGTH else 0f)
            },
        )
    }
    if (strength > 0f) {
        Text(
            text = stringResource(R.string.editor_strength, (strength * 100).roundToInt()),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = strength,
            onValueChange = { value -> onStrength(value.coerceIn(0.1f, 1f)) },
            valueRange = 0.1f..1f,
        )
    }
}

@Composable
private fun PlainPreview(bitmap: Bitmap, colorFilter: ColorFilter?) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
    )
}

/**
 * The full warped canvas with white uncovered corners, plus the same-aspect
 * rectangle that will actually be saved. This mirrors Lightroom's geometry
 * preview: the user sees the correction and its minimal crop in one view.
 */
@Composable
private fun AutoGeometryPreview(bitmap: Bitmap, layout: UprightLayout?) {
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        if (layout != null) {
            Canvas(Modifier.fillMaxSize()) {
                val frame = fitFrame(
                    size = size,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                )
                if (frame.width <= 0f || frame.height <= 0f) return@Canvas
                val rect = frame.toScreen(layout.constrainedCrop)
                val shade = Color.Black.copy(alpha = 0.45f)
                drawRect(shade, topLeft = Offset(0f, 0f), size = Size(size.width, rect.top))
                drawRect(
                    shade,
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, size.height - rect.bottom),
                )
                drawRect(
                    shade,
                    topLeft = Offset(0f, rect.top),
                    size = Size(rect.left, rect.height),
                )
                drawRect(
                    shade,
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(size.width - rect.right, rect.height),
                )
                drawRect(
                    color = Color.White,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun CropPreview(
    bitmap: Bitmap,
    crop: CropRect,
    aspect: AspectOption,
    colorFilter: ColorFilter?,
    onCrop: (CropRect) -> Unit,
) {
    val latestCrop by rememberUpdatedState(crop)
    val latestOnCrop by rememberUpdatedState(onCrop)
    var drag by remember { mutableStateOf<CropDrag?>(null) }
    val density = LocalDensity.current
    val hitRadius = with(density) { 32.dp.toPx() }
    val handleRadius = with(density) { 9.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, aspect, hitRadius) {
                    fun currentFrame(): FitFrame = fitFrame(
                        size = Size(size.width.toFloat(), size.height.toFloat()),
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                    )
                    detectDragGestures(
                        onDragStart = { offset ->
                            val frame = currentFrame()
                            if (frame.width <= 0f || frame.height <= 0f) {
                                drag = null
                            } else {
                                val rect = frame.toScreen(latestCrop)
                                drag = when {
                                    distance(offset, rect.topLeft) <= hitRadius ->
                                        CropDrag.Handle(Corner.TOP_LEFT)

                                    distance(offset, rect.topRight) <= hitRadius ->
                                        CropDrag.Handle(Corner.TOP_RIGHT)

                                    distance(offset, rect.bottomRight) <= hitRadius ->
                                        CropDrag.Handle(Corner.BOTTOM_RIGHT)

                                    distance(offset, rect.bottomLeft) <= hitRadius ->
                                        CropDrag.Handle(Corner.BOTTOM_LEFT)

                                    rect.contains(offset) -> CropDrag.Move
                                    else -> null
                                }
                            }
                        },
                        onDragEnd = { drag = null },
                        onDragCancel = { drag = null },
                        onDrag = { change, amount ->
                            val target = drag
                            val frame = currentFrame()
                            if (target == null || frame.width <= 0f || frame.height <= 0f) {
                                return@detectDragGestures
                            }
                            change.consume()
                            when (target) {
                                is CropDrag.Handle -> {
                                    val normalizedAspect = aspect.ratio?.let {
                                        it * bitmap.height / bitmap.width
                                    }
                                    latestOnCrop(
                                        latestCrop.withCorner(
                                            corner = target.corner,
                                            point = frame.toNormalized(change.position),
                                            normalizedAspect = normalizedAspect,
                                        ),
                                    )
                                }

                                CropDrag.Move -> latestOnCrop(
                                    latestCrop.translated(
                                        dx = amount.x / frame.width,
                                        dy = amount.y / frame.height,
                                    ),
                                )
                            }
                        },
                    )
                },
        ) {
            val frame = fitFrame(
                size = size,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
            if (frame.width <= 0f || frame.height <= 0f) return@Canvas
            val rect = frame.toScreen(crop)
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, topLeft = Offset(0f, 0f), size = Size(size.width, rect.top))
            drawRect(
                shade,
                topLeft = Offset(0f, rect.bottom),
                size = Size(size.width, size.height - rect.bottom),
            )
            drawRect(shade, topLeft = Offset(0f, rect.top), size = Size(rect.left, rect.height))
            drawRect(
                shade,
                topLeft = Offset(rect.right, rect.top),
                size = Size(size.width - rect.right, rect.height),
            )
            drawRect(
                color = Color.White,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 2.dp.toPx()),
            )
            for (index in 1..2) {
                val x = rect.left + rect.width * index / 3f
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(x, rect.top),
                    end = Offset(x, rect.bottom),
                    strokeWidth = 1.dp.toPx(),
                )
                val y = rect.top + rect.height * index / 3f
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = Offset(rect.left, y),
                    end = Offset(rect.right, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            listOf(
                rect.topLeft,
                rect.topRight,
                rect.bottomRight,
                rect.bottomLeft,
            ).forEach { drawHandle(it, handleRadius) }
        }
    }
}

@Composable
private fun GeometryPreview(
    bitmap: Bitmap,
    quad: PerspectiveQuad,
    colorFilter: ColorFilter?,
    onQuad: (PerspectiveQuad) -> Unit,
) {
    val latestQuad by rememberUpdatedState(quad)
    val latestOnQuad by rememberUpdatedState(onQuad)
    var dragCorner by remember { mutableStateOf<Corner?>(null) }
    val density = LocalDensity.current
    val hitRadius = with(density) { 32.dp.toPx() }
    val handleRadius = with(density) { 9.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, hitRadius) {
                    fun currentFrame(): FitFrame = fitFrame(
                        size = Size(size.width.toFloat(), size.height.toFloat()),
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                    )
                    detectDragGestures(
                        onDragStart = { offset ->
                            val frame = currentFrame()
                            if (frame.width <= 0f || frame.height <= 0f) {
                                dragCorner = null
                            } else {
                                val points = latestQuad.corners
                                var closest: Corner? = null
                                var closestDistance = Float.MAX_VALUE
                                Corner.entries.forEach { corner ->
                                    val cornerDistance =
                                        distance(offset, frame.toScreen(points[corner.ordinal]))
                                    if (cornerDistance < closestDistance) {
                                        closestDistance = cornerDistance
                                        closest = corner
                                    }
                                }
                                dragCorner = closest?.takeIf { closestDistance <= hitRadius }
                            }
                        },
                        onDragEnd = { dragCorner = null },
                        onDragCancel = { dragCorner = null },
                        onDrag = { change, _ ->
                            val corner = dragCorner
                            val frame = currentFrame()
                            if (corner == null || frame.width <= 0f || frame.height <= 0f) {
                                return@detectDragGestures
                            }
                            change.consume()
                            val candidate = latestQuad.withCorner(
                                corner = corner,
                                point = frame.toNormalized(change.position),
                            )
                            if (candidate.isUsable()) latestOnQuad(candidate)
                        },
                    )
                },
        ) {
            val frame = fitFrame(
                size = size,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
            if (frame.width <= 0f || frame.height <= 0f) return@Canvas
            val screenPoints = quad.corners.map { frame.toScreen(it) }
            val path = Path().apply {
                moveTo(screenPoints[0].x, screenPoints[0].y)
                for (index in 1 until screenPoints.size) {
                    lineTo(screenPoints[index].x, screenPoints[index].y)
                }
                close()
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                addPath(path)
            }
            drawPath(shade, Color.Black.copy(alpha = 0.55f))
            drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))
            screenPoints.forEach { drawHandle(it, handleRadius) }
        }
    }
}

private sealed interface CropDrag {
    data class Handle(val corner: Corner) : CropDrag
    data object Move : CropDrag
}

private data class FitFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun toScreen(point: NormalizedPoint): Offset =
        Offset(left + point.x * width, top + point.y * height)

    fun toNormalized(offset: Offset): NormalizedPoint = NormalizedPoint(
        x = ((offset.x - left) / width).coerceIn(0f, 1f),
        y = ((offset.y - top) / height).coerceIn(0f, 1f),
    )

    fun toScreen(rect: CropRect): Rect = Rect(
        left = left + rect.left * width,
        top = top + rect.top * height,
        right = left + rect.right * width,
        bottom = top + rect.bottom * height,
    )
}

private fun fitFrame(size: Size, imageWidth: Int, imageHeight: Int): FitFrame {
    if (imageWidth <= 0 || imageHeight <= 0 || size.width <= 0f || size.height <= 0f) {
        return FitFrame(0f, 0f, 0f, 0f)
    }
    val scale = min(size.width / imageWidth, size.height / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FitFrame(
        left = (size.width - width) / 2f,
        top = (size.height - height) / 2f,
        width = width,
        height = height,
    )
}

private fun distance(first: Offset, second: Offset): Float =
    (first - second).getDistance()

private fun DrawScope.drawHandle(center: Offset, radius: Float) {
    drawCircle(Color.White, radius, center)
    drawCircle(
        color = Color.Black.copy(alpha = 0.65f),
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx()),
    )
}
