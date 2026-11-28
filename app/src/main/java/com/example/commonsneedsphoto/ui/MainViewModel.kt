package com.example.commonsneedsphoto.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.commonsneedsphoto.CommonsNeedsPhotoApp
import com.example.commonsneedsphoto.BuildConfig
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import com.example.commonsneedsphoto.data.model.distanceMeters
import com.example.commonsneedsphoto.data.network.LoginResult
import com.example.commonsneedsphoto.data.network.TwoFactorMethod
import com.example.commonsneedsphoto.data.network.UploadedFile
import com.example.commonsneedsphoto.data.network.isBotPasswordLogin
import com.example.commonsneedsphoto.data.photo.AutoGeometry
import com.example.commonsneedsphoto.data.photo.CropRect
import com.example.commonsneedsphoto.data.photo.LineSegmentDetector
import com.example.commonsneedsphoto.data.photo.PhotoEditRenderer
import com.example.commonsneedsphoto.data.photo.PhotoProcessor
import com.example.commonsneedsphoto.data.photo.UprightEstimator
import com.example.commonsneedsphoto.data.photo.UprightMode
import com.example.commonsneedsphoto.data.photo.UprightResult
import com.example.commonsneedsphoto.data.store.IgnoredPlace
import com.example.commonsneedsphoto.data.store.IgnoredPlaces
import com.example.commonsneedsphoto.data.store.IgnoredPlacesStore
import com.example.commonsneedsphoto.data.store.NearbyPlacesSnapshot
import com.example.commonsneedsphoto.data.store.NearbyPlacesStore
import com.example.commonsneedsphoto.data.upload.CommonsFileName
import com.example.commonsneedsphoto.data.upload.UploadLicense
import com.example.commonsneedsphoto.data.upload.UploadResumeKey
import com.example.commonsneedsphoto.data.upload.UploadSpec
import com.example.commonsneedsphoto.data.upload.UploadWork
import com.example.commonsneedsphoto.data.upload.WikitextBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class MapTarget(
    val id: Long,
    val center: LatLon,
    /** Null keeps the zoom level the map has when the target is applied. */
    val zoom: Double?,
)

val DEFAULT_CENTER = LatLon(44.4268, 26.1025) // Bucharest
const val INITIAL_RADIUS_KM = 5.0

data class UploadUiState(
    val place: NearbyPlace,
    val fileName: String,
    val description: String,
    val category: String,
    val license: UploadLicense = UploadLicense.CC_BY_SA_4,
    val licenseAccepted: Boolean = false,
    val photo: File? = null,
    val isPreparingPhoto: Boolean = false,
    val isUploading: Boolean = false,
    val isWaitingForNetwork: Boolean = false,
    val isPublishing: Boolean = false,
    val progress: Int = 0,
    val workName: String? = null,
    val resumeKey: String? = null,
    val error: String? = null,
    val uploaded: UploadedFile? = null,
    val isUpdatingWikidata: Boolean = false,
    val wikidataUpdated: Boolean? = null,
    val wikidataError: String? = null,
)

data class LoginUiState(
    val isLoggingIn: Boolean = false,
    val requiresTwoFactor: Boolean = false,
    val twoFactorMethod: TwoFactorMethod? = null,
    val error: String? = null,
)

data class MainUiState(
    val mapCenter: LatLon = DEFAULT_CENTER,
    val viewportRadiusKm: Double = INITIAL_RADIUS_KM,
    val searchedCenter: LatLon? = null,
    val places: List<NearbyPlace> = emptyList(),
    val ignoredPlaces: List<IgnoredPlace> = emptyList(),
    val isLoading: Boolean = false,
    val isLocating: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val showSearchHere: Boolean = false,
    val hasSearched: Boolean = false,
    val mapTarget: MapTarget? = null,
    val userName: String? = null,
    val showLogin: Boolean = false,
    val login: LoginUiState = LoginUiState(),
    val upload: UploadUiState? = null,
    val editor: PhotoEditorUiState? = null,
    val shouldRequestLocationPermission: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CommonsNeedsPhotoApp
    private val container = app.container
    private val prefs = application.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)
    private val nearbyStore: NearbyPlacesStore = container.nearbyPlacesStore
    private val ignoredStore: IgnoredPlacesStore = container.ignoredPlacesStore

    /** Fresh saved results of the last search, or null when there are none or they are stale. */
    private val cachedNearby = nearbyStore.load()
    private var nearbySavedAtMillis = cachedNearby?.savedAtMillis ?: 0L
    private var searchedRadiusKm = cachedNearby?.radiusKm ?: INITIAL_RADIUS_KM

    /**
     * Every place of the current result, including the excluded ones. The map
     * shows only the visible part, but the excluded points stay here and in the
     * saved snapshot so restoring one needs no new query.
     */
    private var allPlaces: List<NearbyPlace> = cachedNearby?.places.orEmpty()
    private var ignored: IgnoredPlaces = ignoredStore.load()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var locateJob: Job? = null
    private var uploadObserverJob: Job? = null
    private var loginJob: Job? = null
    private var pendingCameraFile: File? = null
    private var targetId = 0L

    init {
        // Saved points less than a day old are shown as they are: a restart must not
        // query Wikidata again and replace what the map already has.
        if (cachedNearby == null) {
            when {
                container.locationProvider.hasPermission() -> locate(searchAfter = true)
                prefs.getBoolean(KEY_PERMISSION_ASKED, false) ->
                    searchHere(DEFAULT_CENTER, INITIAL_RADIUS_KM)
            }
        }
    }

    private fun initialState(): MainUiState {
        val latitude = prefs.getFloat(KEY_LAST_LAT, DEFAULT_CENTER.latitude.toFloat()).toDouble()
        val longitude = prefs.getFloat(KEY_LAST_LON, DEFAULT_CENTER.longitude.toFloat()).toDouble()
        val hasPermission = container.locationProvider.hasPermission()
        return MainUiState(
            mapCenter = cachedNearby?.searchedCenter ?: LatLon(latitude, longitude),
            viewportRadiusKm = cachedNearby?.radiusKm ?: INITIAL_RADIUS_KM,
            searchedCenter = cachedNearby?.searchedCenter,
            places = ignored.visible(allPlaces),
            ignoredPlaces = ignored.places,
            hasSearched = cachedNearby != null,
            userName = container.session.userName,
            shouldRequestLocationPermission = !hasPermission &&
                !prefs.getBoolean(KEY_PERMISSION_ASKED, false),
        )
    }

    fun onLocationPermissionResult(
        granted: Boolean,
        searchAfter: Boolean = true,
        keepZoom: Boolean = false,
    ) {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        _state.update { it.copy(shouldRequestLocationPermission = false) }
        if (granted) {
            // With saved points already on the map, a permission granted at start only
            // recentres the map instead of replacing them with a fresh query.
            locate(
                searchAfter = searchAfter && _state.value.places.isEmpty(),
                keepZoom = keepZoom,
            )
        } else {
            _state.update { it.copy(message = app.getString(R.string.location_permission_denied)) }
            if (searchAfter && !_state.value.hasSearched) {
                searchHere(_state.value.mapCenter, INITIAL_RADIUS_KM)
            }
        }
    }

    fun locate(searchAfter: Boolean = true, keepZoom: Boolean = false) {
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            _state.update { it.copy(isLocating = true) }
            val location = container.locationProvider.currentLocation()
            _state.update { it.copy(isLocating = false) }
            if (location != null) {
                val center = LatLon(location.latitude, location.longitude)
                val target = MapTarget(++targetId, center, if (keepZoom) null else LOCATED_ZOOM)
                _state.update { it.copy(mapCenter = center, mapTarget = target, message = null) }
                if (searchAfter) searchHere(center, INITIAL_RADIUS_KM)
            } else {
                _state.update { it.copy(message = app.getString(R.string.location_unavailable)) }
                if (searchAfter && !_state.value.hasSearched) {
                    searchHere(_state.value.mapCenter, INITIAL_RADIUS_KM)
                }
            }
        }
    }

    fun searchHere(center: LatLon, radiusKm: Double) {
        val radius = radiusKm.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, showSearchHere = false) }
            try {
                val places = container.nearbyRepository.findPlacesWithoutPhoto(center, radius)
                persistCenter(center)
                nearbySavedAtMillis = System.currentTimeMillis()
                searchedRadiusKm = radius
                allPlaces = places
                cacheNearby(places, center, radius)
                _state.update {
                    it.copy(
                        places = ignored.visible(places),
                        isLoading = false,
                        hasSearched = true,
                        searchedCenter = center,
                        mapCenter = center,
                        showSearchHere = false,
                        error = null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        searchedCenter = center,
                        error = app.getString(
                            R.string.nearby_error,
                            exception.message ?: exception.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    fun refresh() {
        searchHere(_state.value.mapCenter, _state.value.viewportRadiusKm)
    }

    /**
     * Hides [place] from the map until it is restored in settings. The point stays
     * in the saved snapshot, so the settings list can put it back without a new search.
     */
    fun ignorePlace(place: NearbyPlace) {
        if (_state.value.upload?.isUploading == true) return
        ignored = ignored.add(place)
        ignoredStore.save(ignored)
        dismissUpload()
        _state.update {
            it.copy(
                places = ignored.visible(allPlaces),
                ignoredPlaces = ignored.places,
                message = app.getString(R.string.place_ignored, place.label),
            )
        }
    }

    /** Puts an excluded point back on the map, if it is still in the current result. */
    fun restorePlace(qid: String) {
        ignored = ignored.remove(qid)
        ignoredStore.save(ignored)
        _state.update {
            it.copy(
                places = ignored.visible(allPlaces),
                ignoredPlaces = ignored.places,
            )
        }
    }

    fun onMapMoved(center: LatLon, radiusKm: Double) {
        val searched = _state.value.searchedCenter
        val moved = searched == null || distanceMeters(
            searched.latitude,
            searched.longitude,
            center.latitude,
            center.longitude,
        ) > 250.0
        _state.update {
            it.copy(
                mapCenter = center,
                viewportRadiusKm = radiusKm.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM),
                showSearchHere = moved && !it.isLoading,
            )
        }
    }

    fun onPlaceSelected(place: NearbyPlace) {
        val description = buildString {
            append(place.label)
            place.description?.let { append(", ").append(it) }
        }
        _state.update {
            it.copy(
                upload = UploadUiState(
                    place = place,
                    fileName = CommonsFileName.defaultName(place),
                    description = description,
                    category = place.commonsCategory.orEmpty(),
                ),
                editor = null,
            )
        }
    }

    fun dismissUpload() {
        val current = _state.value.upload ?: return
        if (current.isUploading && !current.isWaitingForNetwork) return
        if (current.isUploading) {
            // The work has not started yet; closing the sheet cancels the queued upload.
            current.workName?.let { UploadWork.cancel(app, it) }
        } else {
            // Without the sheet there is no retry UI, so a saved chunk position is useless.
            current.resumeKey?.let { resumeKey ->
                viewModelScope.launch(Dispatchers.IO) {
                    container.uploadResumeStore.clear(resumeKey)
                }
            }
        }
        uploadObserverJob?.cancel()
        uploadObserverJob = null
        current.photo?.delete()
        _state.update { it.copy(upload = null, editor = null) }
    }

    fun newCameraTarget(): Uri {
        pendingCameraFile?.delete()
        val file = PhotoProcessor.createCameraTarget(app)
        pendingCameraFile = file
        return PhotoProcessor.uriFor(app, file)
    }

    fun onCameraResult(success: Boolean) {
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            onPhotoSelected(PhotoProcessor.uriFor(app, file), sourceToDelete = file)
        } else {
            file?.delete()
        }
    }

    fun onPhotoSelected(uri: Uri, sourceToDelete: File? = null) {
        val upload = _state.value.upload ?: return
        viewModelScope.launch {
            _state.update { it.copy(upload = it.upload?.copy(isPreparingPhoto = true, error = null)) }
            try {
                val file = withContext(Dispatchers.IO) {
                    PhotoProcessor.process(
                        context = app,
                        source = uri,
                        fallbackCoordinates = upload.place.latitude to upload.place.longitude,
                    )
                }
                sourceToDelete?.delete()
                if (_state.value.upload == null) {
                    file.delete()
                    return@launch
                }
                _state.value.upload?.photo?.takeIf { it != file }?.delete()
                _state.update {
                    it.copy(upload = it.upload?.copy(photo = file, isPreparingPhoto = false))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                sourceToDelete?.delete()
                _state.update {
                    it.copy(
                        upload = it.upload?.copy(
                            isPreparingPhoto = false,
                            error = app.getString(
                                R.string.photo_error,
                                exception.message ?: exception.javaClass.simpleName,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun removePhoto() {
        _state.value.upload?.photo?.delete()
        _state.update { it.copy(upload = it.upload?.copy(photo = null), editor = null) }
    }

    fun editPhoto() {
        val upload = _state.value.upload ?: return
        val photo = upload.photo ?: return
        if (upload.isUploading || upload.isPreparingPhoto) return
        if (_state.value.editor != null) return
        _state.update { it.copy(editor = PhotoEditorUiState(source = photo)) }
    }

    fun updateEditor(transform: (PhotoEditorUiState) -> PhotoEditorUiState) {
        _state.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor).copy(error = null, notice = null))
        }
    }

    fun dismissEditor() {
        _state.update { state ->
            val editor = state.editor ?: return@update state
            if (editor.isSaving) state else state.copy(editor = null)
        }
    }

    fun autoDetectGeometry() {
        val editor = _state.value.editor ?: return
        if (editor.isDetecting || editor.isSaving) return
        _state.update {
            it.copy(editor = editor.copy(isDetecting = true, error = null, notice = null))
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                var preview: Bitmap? = null
                var rotated: Bitmap? = null
                try {
                    val sourceBitmap = PhotoEditRenderer.decodeAnalysis(editor.source)
                        ?: return@withContext null
                    preview = sourceBitmap
                    rotated = PhotoEditRenderer.rotate(sourceBitmap, editor.rotationDegrees)
                    val width = rotated.width
                    val height = rotated.height
                    val pixels = IntArray(width * height)
                    rotated.getPixels(pixels, 0, width, 0, 0, width, height)
                    val grey = LineSegmentDetector.greyscale(pixels, width, height)
                    val segments = LineSegmentDetector.detect(grey, width, height)
                    UprightEstimator.estimate(
                        segments = segments,
                        width = width,
                        height = height,
                        focalRatio = focalRatio(editor.source, width, height),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (exception: Exception) {
                    null
                } finally {
                    if (rotated != null && rotated !== preview) rotated.recycle()
                    preview?.recycle()
                }
            }
            if (result == null) {
                _state.update { state ->
                    val current = state.editor ?: return@update state
                    if (current.source != editor.source) return@update state
                    state.copy(
                        editor = current.copy(
                            isDetecting = false,
                            error = app.getString(R.string.editor_geometry_no_lines),
                        ),
                    )
                }
                return@launch
            }
            val log = when (result) {
                is UprightResult.Corrected -> result.log
                is UprightResult.AlreadyStraight -> result.log
                is UprightResult.NotFound -> result.log
            }
            android.util.Log.d("UprightEstimator", log.joinToString("\n"))
            _state.update { state ->
                val current = state.editor ?: return@update state
                if (current.source != editor.source) return@update state
                if (current.rotationDegrees != editor.rotationDegrees) {
                    return@update state.copy(editor = current.copy(isDetecting = false))
                }
                when (result) {
                    is UprightResult.Corrected -> state.copy(
                        editor = current.copy(
                            isDetecting = false,
                            autoGeometry = result.geometry,
                            quad = null,
                            crop = CropRect.FULL,
                            notice = noticeFor(result.geometry),
                            error = null,
                        ),
                    )

                    is UprightResult.AlreadyStraight -> state.copy(
                        editor = current.copy(
                            isDetecting = false,
                            notice = app.getString(R.string.editor_geometry_already_straight),
                            error = null,
                        ),
                    )

                    is UprightResult.NotFound -> state.copy(
                        editor = current.copy(
                            isDetecting = false,
                            error = app.getString(
                                if (result.reason == UprightResult.Reason.NO_LINES) {
                                    R.string.editor_geometry_no_lines
                                } else {
                                    R.string.editor_geometry_too_strong
                                },
                            ),
                            notice = null,
                        ),
                    )
                }
            }
        }
    }

    private fun noticeFor(geometry: AutoGeometry): String = when (geometry.mode) {
        UprightMode.LEVEL -> app.getString(
            R.string.editor_geometry_leveled,
            Math.toDegrees(abs(geometry.roll)),
        )
        UprightMode.VERTICAL -> app.getString(R.string.editor_geometry_vertical)
        UprightMode.FULL -> app.getString(R.string.editor_geometry_full)
    }

    private fun focalRatio(source: File, width: Int, height: Int): Double {
        val focal35 = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                0,
            )
        }.getOrDefault(0)
        if (focal35 <= 0) return UprightEstimator.DEFAULT_FOCAL_RATIO
        val longSide = max(width, height).toDouble()
        return (
            focal35 / 43.27 * hypot(width.toDouble(), height.toDouble()) / longSide
            ).coerceIn(0.45, 2.5)
    }

    fun saveEditedPhoto() {
        val editor = _state.value.editor ?: return
        if (editor.isSaving || editor.isDetecting) return
        if (!editor.edits.hasEdits) {
            // Nothing was changed: keep the original JPEG instead of re-encoding it.
            _state.update { it.copy(editor = null) }
            return
        }
        _state.update { it.copy(editor = editor.copy(isSaving = true, error = null)) }
        viewModelScope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    val dir = File(app.filesDir, "photos").apply { mkdirs() }
                    val file = File(dir, "upload_edited_${System.currentTimeMillis()}.jpg")
                    try {
                        PhotoEditRenderer.render(editor.source, file, editor.edits)
                    } catch (exception: Exception) {
                        file.delete()
                        throw exception
                    }
                    file
                }
                val upload = _state.value.upload
                val currentEditor = _state.value.editor
                if (upload == null ||
                    upload.photo != editor.source ||
                    currentEditor?.source != editor.source
                ) {
                    output.delete()
                    _state.update { state ->
                        val current = state.editor ?: return@update state
                        if (current.source != editor.source) return@update state
                        if (state.upload == null) {
                            state.copy(editor = null)
                        } else {
                            state.copy(editor = current.copy(isSaving = false))
                        }
                    }
                    return@launch
                }
                editor.source.delete()
                _state.update {
                    it.copy(
                        upload = it.upload?.copy(photo = output, error = null),
                        editor = null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                _state.update {
                    val current = it.editor ?: return@update it
                    it.copy(
                        editor = current.copy(
                            isSaving = false,
                            error = app.getString(
                                R.string.editor_save_failed,
                                exception.message ?: exception.javaClass.simpleName,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun updateUpload(transform: (UploadUiState) -> UploadUiState) {
        _state.update { it.copy(upload = it.upload?.let(transform)) }
    }

    fun login(loginName: String, password: String) {
        if (loginName.isBlank() || password.isBlank()) {
            _state.update {
                it.copy(login = LoginUiState(error = app.getString(R.string.login_empty_fields)))
            }
            return
        }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _state.update { it.copy(login = LoginUiState(isLoggingIn = true)) }
            try {
                when (val result = container.commonsApi.login(loginName, password)) {
                    is LoginResult.Success -> {
                        if (isBotPasswordLogin(loginName)) {
                            // A bot-password session cannot mint a centralauthtoken for
                            // Wikidata, so sign in there with the same (global) bot
                            // password. Best effort: uploading to Commons already works.
                            try {
                                container.wikidataApi.loginWithBotPassword(loginName, password)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                // The P18 step reports the missing Wikidata session.
                            }
                        }
                        finishLogin(result.userName)
                    }
                    is LoginResult.VerificationRequired -> _state.update {
                        it.copy(
                            login = LoginUiState(
                                requiresTwoFactor = true,
                                twoFactorMethod = result.method,
                            ),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        login = LoginUiState(
                            error = app.getString(
                                R.string.login_failed,
                                exception.message ?: exception.javaClass.simpleName,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun verifyTwoFactor(code: String) {
        if (code.isBlank()) {
            _state.update {
                it.copy(login = it.login.copy(error = app.getString(R.string.login_missing_code)))
            }
            return
        }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _state.update { it.copy(login = it.login.copy(isLoggingIn = true, error = null)) }
            try {
                when (val result = container.commonsApi.completeTwoFactor(code)) {
                    is LoginResult.Success -> finishLogin(result.userName)
                    is LoginResult.VerificationRequired -> _state.update {
                        it.copy(
                            login = it.login.copy(
                                isLoggingIn = false,
                                requiresTwoFactor = true,
                                twoFactorMethod = result.method,
                                error = app.getString(R.string.login_wrong_code),
                            ),
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (exception: Exception) {
                _state.update {
                    it.copy(
                        login = it.login.copy(
                            isLoggingIn = false,
                            error = app.getString(
                                R.string.login_failed,
                                exception.message ?: exception.javaClass.simpleName,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun finishLogin(name: String) {
        _state.update {
            it.copy(
                userName = name,
                showLogin = false,
                login = LoginUiState(),
                upload = it.upload?.copy(error = null),
                message = app.getString(R.string.logged_in_as, name),
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            container.commonsApi.logout()
            _state.update { it.copy(userName = null, message = null) }
        }
    }

    fun showLogin() {
        container.commonsApi.resetLogin()
        _state.update { it.copy(showLogin = true, login = LoginUiState()) }
    }

    fun dismissLogin() {
        loginJob?.cancel()
        container.commonsApi.resetLogin()
        _state.update { it.copy(showLogin = false, login = LoginUiState()) }
    }

    fun backToLoginForm() {
        loginJob?.cancel()
        container.commonsApi.resetLogin()
        _state.update { it.copy(login = LoginUiState()) }
    }

    fun upload() {
        val upload = _state.value.upload ?: return
        if (upload.isUploading) return
        if (upload.photo == null) {
            setUploadError(R.string.upload_missing_file)
            return
        }
        if (upload.fileName.isBlank()) {
            setUploadError(R.string.upload_missing_name)
            return
        }
        if (!upload.licenseAccepted) {
            setUploadError(R.string.upload_missing_license)
            return
        }
        val userName = _state.value.userName
        if (userName == null) {
            _state.update { it.copy(showLogin = true) }
            return
        }

        val fileName = CommonsFileName.sanitize(
            upload.fileName,
            fallback = CommonsFileName.defaultName(upload.place),
        )
        val wikitext = WikitextBuilder.build(
            place = upload.place,
            description = upload.description,
            author = userName.substringBefore('@'),
            category = upload.category,
            license = upload.license,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val photo = upload.photo
        val spec = UploadSpec(
            file = photo,
            fileName = fileName,
            wikitext = wikitext,
            comment = UPLOAD_COMMENT,
            qid = upload.place.qid,
            placeLabel = upload.place.label,
            resumeKey = UploadResumeKey.of(upload.place.qid, photo),
        )

        uploadObserverJob?.cancel()
        try {
            val handle = UploadWork.enqueue(app, spec)
            _state.update {
                it.copy(
                    upload = it.upload?.copy(
                        isUploading = true,
                        isWaitingForNetwork = true,
                        isPublishing = false,
                        progress = 0,
                        workName = handle.uniqueName,
                        resumeKey = spec.resumeKey,
                        error = null,
                    ),
                )
            }
            observeUploadWork(handle.id)
        } catch (exception: Exception) {
            _state.update {
                it.copy(
                    upload = it.upload?.copy(
                        isUploading = false,
                        isWaitingForNetwork = false,
                        error = app.getString(
                            R.string.upload_failed,
                            exception.message ?: exception.javaClass.simpleName,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Keeps the sheet in sync with the WorkManager request. The work itself runs outside
     * the ViewModel, so rotation and process restarts do not interrupt the upload.
     */
    private fun observeUploadWork(id: UUID) {
        uploadObserverJob?.cancel()
        uploadObserverJob = viewModelScope.launch {
            UploadWork.observe(app, id).collect { info ->
                info?.let(::applyUploadWorkInfo)
            }
        }
    }

    private fun applyUploadWorkInfo(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            -> _state.update { state ->
                val current = state.upload ?: return@update state
                state.copy(
                    upload = current.copy(
                        isUploading = true,
                        isWaitingForNetwork = true,
                        isPublishing = false,
                    ),
                )
            }

            WorkInfo.State.RUNNING -> {
                val progress = info.progress.getInt(UploadWork.KEY_PROGRESS, 0)
                    .coerceIn(0, 100)
                val phase = info.progress.getString(UploadWork.KEY_PHASE)
                val uploadedName = info.progress.getString(UploadWork.KEY_UPLOADED_FILE_NAME)
                val pageUrl = info.progress.getString(UploadWork.KEY_PAGE_URL)
                val published = if (uploadedName != null && pageUrl != null) {
                    UploadedFile(uploadedName, pageUrl)
                } else {
                    null
                }
                val currentQid = _state.value.upload?.place?.qid
                if (published != null && currentQid != null) {
                    allPlaces = allPlaces.filterNot { it.qid == currentQid }
                }
                _state.update { state ->
                    val current = state.upload ?: return@update state
                    val result = published ?: current.uploaded
                    state.copy(
                        upload = current.copy(
                            isUploading = true,
                            isWaitingForNetwork = false,
                            isPublishing = phase == UploadWork.PHASE_PUBLISH,
                            isUpdatingWikidata = phase == UploadWork.PHASE_WIKIDATA,
                            progress = progress,
                            uploaded = result,
                        ),
                        places = if (published != null) {
                            ignored.visible(allPlaces)
                        } else {
                            state.places
                        },
                    )
                }
                if (published != null) cacheCurrentNearby()
            }

            WorkInfo.State.SUCCEEDED -> {
                val data = info.outputData
                val uploadedName = data.getString(UploadWork.KEY_UPLOADED_FILE_NAME)
                val pageUrl = data.getString(UploadWork.KEY_PAGE_URL)
                val p18Updated = data.getBoolean(UploadWork.KEY_P18_UPDATED, false)
                val p18Code = data.getString(UploadWork.KEY_P18_ERROR_CODE)
                val p18Message = data.getString(UploadWork.KEY_P18_ERROR)
                val published = if (uploadedName != null && pageUrl != null) {
                    UploadedFile(uploadedName, pageUrl)
                } else {
                    null
                }
                _state.value.upload?.resumeKey?.let { resumeKey ->
                    viewModelScope.launch(Dispatchers.IO) {
                        container.uploadResumeStore.clearPublished(resumeKey)
                    }
                }
                val currentUpload = _state.value.upload
                val result = published ?: currentUpload?.uploaded
                if (result != null && currentUpload != null) {
                    allPlaces = allPlaces.filterNot { it.qid == currentUpload.place.qid }
                }
                _state.update { state ->
                    val current = state.upload ?: return@update state
                    state.copy(
                        upload = current.copy(
                            isUploading = false,
                            isWaitingForNetwork = false,
                            isPublishing = false,
                            isUpdatingWikidata = false,
                            progress = 100,
                            uploaded = result,
                            fileName = result?.fileName ?: current.fileName,
                            wikidataUpdated = p18Updated,
                            wikidataError = if (p18Updated) {
                                null
                            } else {
                                p18ErrorMessage(p18Code, p18Message)
                            },
                        ),
                        places = if (result != null) {
                            ignored.visible(allPlaces)
                        } else {
                            state.places
                        },
                    )
                }
                cacheCurrentNearby()
            }

            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            -> {
                val kind = info.outputData.getString(UploadWork.KEY_ERROR_KIND)
                val sessionExpired = kind == UploadWork.ERROR_KIND_SESSION
                if (sessionExpired) {
                    container.session.clear()
                }
                val message = when {
                    sessionExpired -> app.getString(R.string.login_session_expired)
                    kind == UploadWork.ERROR_KIND_NETWORK ->
                        app.getString(R.string.upload_network_retries_failed)
                    info.state == WorkInfo.State.CANCELLED ->
                        app.getString(R.string.upload_cancelled)
                    else -> app.getString(
                        R.string.upload_failed,
                        info.outputData.getString(UploadWork.KEY_ERROR)
                            ?: app.getString(R.string.upload_failed_unknown),
                    )
                }
                _state.update { state ->
                    state.copy(
                        userName = if (sessionExpired) null else state.userName,
                        upload = state.upload?.copy(
                            isUploading = false,
                            isWaitingForNetwork = false,
                            isPublishing = false,
                            isUpdatingWikidata = false,
                            error = message,
                        ),
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun p18ErrorMessage(code: String?, message: String?): String = when (code) {
        "assertuserfailed", "mustbeloggedin", "notloggedin", "notattached", "badtoken" ->
            app.getString(R.string.wikidata_not_logged_in)
        "badsession" ->
            app.getString(R.string.wikidata_bot_password_session)
        "permissiondenied", "badaccess-groups" ->
            app.getString(R.string.wikidata_no_permission)
        else -> message ?: app.getString(R.string.upload_failed_unknown)
    }

    private fun setUploadError(resource: Int) {
        _state.update {
            it.copy(upload = it.upload?.copy(error = app.getString(resource)))
        }
    }

    private fun persistCenter(center: LatLon) {
        prefs.edit()
            .putFloat(KEY_LAST_LAT, center.latitude.toFloat())
            .putFloat(KEY_LAST_LON, center.longitude.toFloat())
            .apply()
    }

    /**
     * Saves a search result for the next start. An empty result clears the cache
     * instead of keeping points from an older query. [nearbySavedAtMillis] is not
     * touched here, so removing points after an upload does not extend the day.
     */
    private fun cacheNearby(places: List<NearbyPlace>, center: LatLon, radiusKm: Double) {
        if (places.isEmpty()) {
            nearbyStore.clear()
            return
        }
        nearbyStore.save(
            NearbyPlacesSnapshot(
                savedAtMillis = nearbySavedAtMillis,
                searchedCenter = center,
                radiusKm = radiusKm,
                places = places,
            ),
        )
    }

    /**
     * Re-saves the current result after a published upload removed a point from
     * it. [allPlaces] is written instead of the visible list, so excluded points
     * stay in the snapshot and can be restored from settings.
     */
    private fun cacheCurrentNearby() {
        val current = _state.value
        val center = current.searchedCenter ?: return
        cacheNearby(allPlaces, center, searchedRadiusKm)
    }

    companion object {
        private const val MIN_RADIUS_KM = 0.5
        private const val MAX_RADIUS_KM = 30.0
        private const val LOCATED_ZOOM = 14.5
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        private const val KEY_PERMISSION_ASKED = "permission_asked"
        private const val UPLOAD_COMMENT = "Uploaded with the Commons needs photo MVP"
    }
}
