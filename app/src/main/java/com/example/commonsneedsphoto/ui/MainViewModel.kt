package com.example.commonsneedsphoto.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.commonsneedsphoto.CommonsNeedsPhotoApp
import com.example.commonsneedsphoto.BuildConfig
import com.example.commonsneedsphoto.R
import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import com.example.commonsneedsphoto.data.model.distanceMeters
import com.example.commonsneedsphoto.data.network.UploadedFile
import com.example.commonsneedsphoto.data.photo.PhotoProcessor
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

data class MapTarget(
    val id: Long,
    val center: LatLon,
    val zoom: Double,
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
    val error: String? = null,
)

data class MainUiState(
    val mapCenter: LatLon = DEFAULT_CENTER,
    val viewportRadiusKm: Double = INITIAL_RADIUS_KM,
    val searchedCenter: LatLon? = null,
    val places: List<NearbyPlace> = emptyList(),
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
    val shouldRequestLocationPermission: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CommonsNeedsPhotoApp
    private val container = app.container
    private val prefs = application.getSharedPreferences("app_state", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var locateJob: Job? = null
    private var uploadObserverJob: Job? = null
    private var loginJob: Job? = null
    private var pendingCameraFile: File? = null
    private var targetId = 0L

    init {
        when {
            container.locationProvider.hasPermission() -> locate(searchAfter = true)
            prefs.getBoolean(KEY_PERMISSION_ASKED, false) ->
                searchHere(DEFAULT_CENTER, INITIAL_RADIUS_KM)
        }
    }

    private fun initialState(): MainUiState {
        val latitude = prefs.getFloat(KEY_LAST_LAT, DEFAULT_CENTER.latitude.toFloat()).toDouble()
        val longitude = prefs.getFloat(KEY_LAST_LON, DEFAULT_CENTER.longitude.toFloat()).toDouble()
        val hasPermission = container.locationProvider.hasPermission()
        return MainUiState(
            mapCenter = LatLon(latitude, longitude),
            userName = container.session.userName,
            shouldRequestLocationPermission = !hasPermission &&
                !prefs.getBoolean(KEY_PERMISSION_ASKED, false),
        )
    }

    fun onLocationPermissionResult(granted: Boolean, searchAfter: Boolean = true) {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        _state.update { it.copy(shouldRequestLocationPermission = false) }
        if (granted) {
            locate(searchAfter = searchAfter)
        } else {
            _state.update { it.copy(message = app.getString(R.string.location_permission_denied)) }
            if (searchAfter && !_state.value.hasSearched) {
                searchHere(_state.value.mapCenter, INITIAL_RADIUS_KM)
            }
        }
    }

    fun locate(searchAfter: Boolean = true) {
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            _state.update { it.copy(isLocating = true) }
            val location = container.locationProvider.currentLocation()
            _state.update { it.copy(isLocating = false) }
            if (location != null) {
                val center = LatLon(location.latitude, location.longitude)
                val target = MapTarget(++targetId, center, LOCATED_ZOOM)
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
                _state.update {
                    it.copy(
                        places = places,
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
            )
        }
    }

    fun dismissUpload() {
        val current = _state.value.upload ?: return
        if (current.isUploading && !current.isWaitingForNetwork) return
        if (current.isUploading) {
            // The work has not started yet; closing the sheet cancels the queued upload.
            current.workName?.let { UploadWork.cancel(app, it) }
        }
        uploadObserverJob?.cancel()
        uploadObserverJob = null
        current.photo?.delete()
        _state.update { it.copy(upload = null) }
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
        _state.update { it.copy(upload = it.upload?.copy(photo = null)) }
    }

    fun updateUpload(transform: (UploadUiState) -> UploadUiState) {
        _state.update { it.copy(upload = it.upload?.let(transform)) }
    }

    fun login(loginName: String, password: String) {
        if (loginName.isBlank() || password.isBlank()) {
            _state.update {
                it.copy(login = LoginUiState(error = app.getString(R.string.login_failed, "empty fields")))
            }
            return
        }
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _state.update { it.copy(login = LoginUiState(isLoggingIn = true)) }
            try {
                val name = container.commonsApi.login(loginName, password)
                _state.update {
                    it.copy(
                        userName = name,
                        showLogin = false,
                        login = LoginUiState(),
                        upload = it.upload?.copy(error = null),
                        message = app.getString(R.string.logged_in_as, name),
                    )
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

    fun logout() {
        viewModelScope.launch {
            container.commonsApi.logout()
            _state.update { it.copy(userName = null, message = null) }
        }
    }

    fun showLogin() {
        _state.update { it.copy(showLogin = true, login = LoginUiState()) }
    }

    fun dismissLogin() {
        loginJob?.cancel()
        _state.update { it.copy(showLogin = false, login = LoginUiState()) }
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
                            state.places.filterNot { it.qid == current.place.qid }
                        } else {
                            state.places
                        },
                    )
                }
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
                _state.update { state ->
                    val current = state.upload ?: return@update state
                    val result = published ?: current.uploaded
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
                            state.places.filterNot { it.qid == current.place.qid }
                        } else {
                            state.places
                        },
                    )
                }
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
        "assertuserfailed", "mustbeloggedin", "notloggedin" ->
            app.getString(R.string.wikidata_not_logged_in)
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
