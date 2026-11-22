package com.example.commonsneedsphoto.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import com.example.commonsneedsphoto.data.model.distanceMeters
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasLocationPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var showPermissionRationale by rememberSaveable { mutableStateOf(false) }
    var permissionForLocateOnly by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        showPermissionRationale = false
        viewModel.onLocationPermissionResult(
            granted = granted,
            searchAfter = !permissionForLocateOnly,
        )
        permissionForLocateOnly = false
    }

    LaunchedEffect(state.shouldRequestLocationPermission) {
        if (state.shouldRequestLocationPermission) {
            showPermissionRationale = true
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when {
                                state.isLocating -> stringResource(R.string.locating)
                                state.isLoading -> stringResource(R.string.nearby_loading)
                                state.hasSearched -> pluralStringResource(
                                    R.plurals.nearby_count,
                                    state.places.size,
                                    state.places.size,
                                )
                                else -> stringResource(R.string.map_title)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { showAccountDialog = true }) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.account))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (hasLocationPermission) {
                        viewModel.locate(searchAfter = false)
                    } else {
                        permissionForLocateOnly = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = stringResource(R.string.my_location),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            NearbyMap(
                initialCenter = remember { state.mapCenter },
                places = state.places,
                mapTarget = state.mapTarget,
                hasLocationPermission = hasLocationPermission,
                onMapMoved = viewModel::onMapMoved,
                onPlaceTap = viewModel::onPlaceSelected,
            )

            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    ErrorCard(
                        message = error,
                        onRetry = viewModel::refresh,
                        onDismiss = viewModel::dismissError,
                    )
                }
                if (state.showSearchHere) {
                    Button(onClick = { viewModel.searchHere(state.mapCenter, state.viewportRadiusKm) }) {
                        Text(stringResource(R.string.search_this_area))
                    }
                }
            }

            if (!state.hasSearched && !state.isLoading && !state.isLocating) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.map_intro),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.searchHere(state.mapCenter, 5.0) }) {
                            Text(stringResource(R.string.search_this_area))
                        }
                    }
                }
            } else if (state.hasSearched && !state.isLoading && state.places.isEmpty() && state.error == null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.nearby_empty),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = {
                showPermissionRationale = false
                viewModel.onLocationPermissionResult(granted = false)
            },
            title = { Text(stringResource(R.string.location_permission_title)) },
            text = { Text(stringResource(R.string.location_permission_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        permissionForLocateOnly = false
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.location_permission_grant))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionRationale = false
                        viewModel.onLocationPermissionResult(granted = false)
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAccountDialog) {
        AccountDialog(
            userName = state.userName,
            onLogin = {
                showAccountDialog = false
                viewModel.showLogin()
            },
            onLogout = {
                viewModel.logout()
                showAccountDialog = false
            },
            onDismiss = { showAccountDialog = false },
        )
    }

    state.upload?.let { upload ->
        UploadSheet(
            state = upload,
            userName = state.userName,
            origin = state.mapCenter,
            viewModel = viewModel,
        )
    }

    if (state.showLogin) {
        LoginDialog(
            state = state.login,
            onLogin = viewModel::login,
            onDismiss = viewModel::dismissLogin,
        )
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun AccountDialog(
    userName: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account)) },
        text = {
            Column {
                Text(
                    if (userName != null) {
                        stringResource(R.string.logged_in_as, userName)
                    } else {
                        stringResource(R.string.not_logged_in)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (userName != null) {
                TextButton(onClick = onLogout) {
                    Text(stringResource(R.string.logout))
                }
            } else {
                Button(onClick = onLogin) {
                    Text(stringResource(R.string.login))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun NearbyMap(
    initialCenter: LatLon,
    places: List<NearbyPlace>,
    mapTarget: MapTarget?,
    hasLocationPermission: Boolean,
    onMapMoved: (LatLon, Double) -> Unit,
    onPlaceTap: (NearbyPlace) -> Unit,
) {
    val holder = remember { OsmMapHolder() }
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { context ->
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(13.0)
                controller.setCenter(GeoPoint(initialCenter.latitude, initialCenter.longitude))
                val markers = NearbyMarkersOverlay(
                    context = context,
                    onPlaceTap = onPlaceTap,
                    onClusterTap = cluster@{ clusterPlaces ->
                        val map = holder.mapView ?: return@cluster
                        if (clusterPlaces.isNotEmpty()) {
                            val latitude = clusterPlaces.sumOf { it.latitude } / clusterPlaces.size
                            val longitude = clusterPlaces.sumOf { it.longitude } / clusterPlaces.size
                            map.controller.animateTo(
                                GeoPoint(latitude, longitude),
                                (map.zoomLevelDouble + 2.0).coerceAtMost(19.0),
                                MAP_ANIMATION_MS,
                            )
                        }
                    },
                )
                overlays.add(markers)
                overlays.add(CopyrightOverlay(context))
                holder.mapView = this
                holder.markers = markers
                holder.lifecycleObserver = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> onResume()
                        Lifecycle.Event.ON_PAUSE -> onPause()
                        else -> Unit
                    }
                }.also { lifecycleOwner.lifecycle.addObserver(it) }
                addMapListener(
                    DelayedMapListener(
                        object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                reportViewport()
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                reportViewport()
                                return false
                            }

                            private fun reportViewport() {
                                val map = holder.mapView ?: return
                                val center = map.mapCenter
                                onMapMoved(
                                    LatLon(center.latitude, center.longitude),
                                    viewportRadiusKm(map),
                                )
                            }
                        },
                        VIEWPORT_DEBOUNCE_MS,
                    ),
                )
                onResume()
            }
        },
        update = { mapView ->
            holder.markers?.setPlaces(places)
            mapView.invalidate()

            if (hasLocationPermission && holder.myLocation == null) {
                val overlay = MyLocationNewOverlay(GpsMyLocationProvider(mapView.context), mapView)
                val density = mapView.context.resources.displayMetrics.density
                val dotSize = (24 * density).toInt().coerceAtLeast(1)
                val dot = ContextCompat.getDrawable(mapView.context, R.drawable.ic_location_dot)
                    ?.toBitmap(dotSize, dotSize)
                val hiddenArrow = ContextCompat.getDrawable(
                    mapView.context,
                    R.drawable.ic_location_arrow_hidden,
                )?.toBitmap(1, 1)
                if (dot != null && hiddenArrow != null) {
                    overlay.setDirectionArrow(dot, hiddenArrow)
                }
                overlay.enableMyLocation()
                mapView.overlays.add(overlay)
                holder.myLocation = overlay
                mapView.invalidate()
            }

            if (mapTarget != null && mapTarget.id != holder.lastTargetId) {
                holder.lastTargetId = mapTarget.id
                mapView.controller.animateTo(
                    GeoPoint(mapTarget.center.latitude, mapTarget.center.longitude),
                    mapTarget.zoom,
                    MAP_ANIMATION_MS,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(lifecycleOwner) {
        onDispose {
            holder.lifecycleObserver?.let(lifecycleOwner.lifecycle::removeObserver)
            holder.mapView?.onDetach()
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun viewportRadiusKm(mapView: MapView): Double {
    val box = mapView.boundingBox
    val center = mapView.mapCenter
    val meters = distanceMeters(
        center.latitude,
        center.longitude,
        box.latNorth,
        box.lonEast,
    )
    return (meters / 1000.0).coerceIn(0.5, 30.0)
}

private class OsmMapHolder {
    var mapView: MapView? = null
    var markers: NearbyMarkersOverlay? = null
    var myLocation: MyLocationNewOverlay? = null
    var lifecycleObserver: LifecycleEventObserver? = null
    var lastTargetId = -1L
}

private const val VIEWPORT_DEBOUNCE_MS = 400L
private const val MAP_ANIMATION_MS = 700L

/**
 * Draws clustered pins directly on the canvas. Points are grouped by screen
 * cells, so a dense city does not turn into a wall of overlapping pins; taps
 * on a cluster zoom in, taps on a single pin open the upload sheet.
 */
private class NearbyMarkersOverlay(
    context: Context,
    private val onPlaceTap: (NearbyPlace) -> Unit,
    private val onClusterTap: (List<NearbyPlace>) -> Unit,
) : Overlay() {
    private val density = context.resources.displayMetrics.density
    private val pin: Bitmap = drawPin(context)
    private val cellSizePx = 84f * density
    private val touchRadiusPx = 34f * density
    private val bubbleRadiusPx = 19f * density
    private val reusablePoint = Point()
    private var places: List<NearbyPlace> = emptyList()

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 81, 0)
    }
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setPlaces(newPlaces: List<NearbyPlace>) {
        places = newPlaces
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || places.isEmpty()) return
        for (cluster in buildClusters(mapView)) {
            if (cluster.places.size == 1) {
                val place = cluster.places.first()
                mapView.projection.toPixels(
                    GeoPoint(place.latitude, place.longitude),
                    reusablePoint,
                )
                if (!isNearViewport(mapView, reusablePoint)) continue
                canvas.drawBitmap(
                    pin,
                    reusablePoint.x - pin.width / 2f,
                    reusablePoint.y - pin.height.toFloat(),
                    null,
                )
            } else {
                val x = (cluster.cellX + 0.5f) * cellSizePx
                val y = (cluster.cellY + 0.5f) * cellSizePx
                if (x < -bubbleRadiusPx || x > mapView.width + bubbleRadiusPx ||
                    y < -bubbleRadiusPx || y > mapView.height + bubbleRadiusPx
                ) {
                    continue
                }
                canvas.drawCircle(x, y, bubbleRadiusPx, bubblePaint)
                canvas.drawCircle(x, y, bubbleRadiusPx, bubbleStrokePaint)
                val label = if (cluster.places.size > 99) "99+" else cluster.places.size.toString()
                val textY = y - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f
                canvas.drawText(label, x, textY, bubbleTextPaint)
            }
        }
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        var closestPlaces: List<NearbyPlace>? = null
        var closestDistance = Float.MAX_VALUE
        for (cluster in buildClusters(mapView)) {
            val centerX: Float
            val centerY: Float
            val threshold: Float
            if (cluster.places.size == 1) {
                val place = cluster.places.first()
                mapView.projection.toPixels(
                    GeoPoint(place.latitude, place.longitude),
                    reusablePoint,
                )
                centerX = reusablePoint.x.toFloat()
                centerY = reusablePoint.y - pin.height / 2f
                threshold = touchRadiusPx
            } else {
                centerX = (cluster.cellX + 0.5f) * cellSizePx
                centerY = (cluster.cellY + 0.5f) * cellSizePx
                threshold = bubbleRadiusPx + 6f * density
            }
            val dx = event.x - centerX
            val dy = event.y - centerY
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared < closestDistance && distanceSquared <= threshold * threshold) {
                closestDistance = distanceSquared
                closestPlaces = cluster.places
            }
        }
        val found = closestPlaces ?: return false
        if (found.size == 1) {
            onPlaceTap(found.first())
        } else {
            onClusterTap(found)
        }
        return true
    }

    private fun buildClusters(mapView: MapView): List<Cluster> {
        val cells = HashMap<Pair<Int, Int>, MutableList<NearbyPlace>>()
        for (place in places) {
            mapView.projection.toPixels(
                GeoPoint(place.latitude, place.longitude),
                reusablePoint,
            )
            if (!isNearViewport(mapView, reusablePoint)) continue
            val cellX = (reusablePoint.x / cellSizePx).toInt()
            val cellY = (reusablePoint.y / cellSizePx).toInt()
            cells.getOrPut(cellX to cellY) { ArrayList() }.add(place)
        }
        return cells.map { (cell, group) -> Cluster(cell.first, cell.second, group) }
    }

    private fun isNearViewport(mapView: MapView, point: Point): Boolean =
        point.x >= -pin.width && point.x <= mapView.width + pin.width &&
            point.y >= -pin.height && point.y <= mapView.height + pin.height

    private fun drawPin(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (30 * density).toInt().coerceAtLeast(1)
        val height = (40 * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)
        if (drawable != null) {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private data class Cluster(
        val cellX: Int,
        val cellY: Int,
        val places: List<NearbyPlace>,
    )
}
