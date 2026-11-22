package com.example.commonsneedsphoto.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Tries to get a fresh fix, then falls back to the most recent last-known
     * location. Returns null when location is unavailable or not permitted.
     */
    suspend fun currentLocation(timeoutMs: Long = 15_000): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val lastKnown = runCatching {
            manager.allProviders
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        }.getOrNull()

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) },
            LocationManager.NETWORK_PROVIDER.takeIf { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) },
        )
        val fresh = if (providers.isEmpty()) {
            null
        } else {
            withTimeoutOrNull(timeoutMs) { requestSingle(manager, providers) }
        }
        return fresh ?: lastKnown
    }

    private suspend fun requestSingle(
        manager: LocationManager,
        providers: List<String>,
    ): Location? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            var requested = false
            for (provider in providers) {
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    requested = true
                }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            if (!requested) continuation.resume(null)
        }
    }
}
