package com.example.commonsneedsphoto

import android.app.Application
import com.example.commonsneedsphoto.data.location.LocationProvider
import com.example.commonsneedsphoto.data.network.CommonsApi
import com.example.commonsneedsphoto.data.network.CommonsSession
import com.example.commonsneedsphoto.data.network.Network
import com.example.commonsneedsphoto.data.network.WikidataApi
import com.example.commonsneedsphoto.data.network.WikidataNearbyRepository
import com.example.commonsneedsphoto.data.photo.PhotoProcessor
import com.example.commonsneedsphoto.data.upload.ChunkedUploader
import com.example.commonsneedsphoto.data.upload.UploadNotifications
import com.example.commonsneedsphoto.data.upload.UploadResumeStore
import org.osmdroid.config.Configuration
import java.io.File

class AppContainer(application: Application) {
    val session = CommonsSession(application)
    val commonsApi = CommonsApi(session)
    val wikidataApi = WikidataApi(session)
    val nearbyRepository = WikidataNearbyRepository()
    val locationProvider = LocationProvider(application)
    val uploadResumeStore = UploadResumeStore(application)
    val chunkedUploader = ChunkedUploader(commonsApi, uploadResumeStore)
}

class CommonsNeedsPhotoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        configureOsmdroid()
        UploadNotifications(this).ensureChannel()
        PhotoProcessor.clearStaleFiles(this)
    }

    private fun configureOsmdroid() {
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = Network.USER_AGENT
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        }
    }
}
