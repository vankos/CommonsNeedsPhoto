package com.example.commonsneedsphoto.data.store

import android.content.Context
import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Result of the last successful nearby search, kept between app starts with the
 * query that produced it, so the map can open on the same points without asking
 * Wikidata again.
 *
 * A snapshot is usable for one day; points older than that are dropped instead of
 * being shown.
 */
@Serializable
data class NearbyPlacesSnapshot(
    val savedAtMillis: Long,
    val searchedCenter: LatLon,
    val radiusKm: Double,
    val places: List<NearbyPlace>,
    /**
     * Layout of the saved places. Bumped when a snapshot needs data that older
     * versions did not write, so an upgrade drops the old snapshot and searches
     * again instead of filtering with fields that are silently missing.
     */
    val version: Int = LEGACY_VERSION,
) {
    fun isUsable(now: Long = System.currentTimeMillis()): Boolean {
        if (version != CURRENT_VERSION) return false
        if (places.isEmpty()) return false
        // A negative age means the device clock moved backwards; the points are still recent.
        return now - savedAtMillis < TTL_MILLIS
    }

    companion object {
        /** Version 2 added the `P279` ancestor labels of the place types. */
        const val CURRENT_VERSION = 2
        const val LEGACY_VERSION = 1
        const val TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * Persists the last nearby snapshot in an app-private preference file.
 *
 * [load] deletes a snapshot that is unreadable or older than
 * [NearbyPlacesSnapshot.TTL_MILLIS], so stale points never reach the map.
 */
class NearbyPlacesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(now: Long = System.currentTimeMillis()): NearbyPlacesSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        val snapshot = runCatching {
            json.decodeFromString<NearbyPlacesSnapshot>(raw)
        }.getOrNull()
        if (snapshot == null || !snapshot.isUsable(now)) {
            clear()
            return null
        }
        return snapshot
    }

    fun save(snapshot: NearbyPlacesSnapshot) {
        val current = snapshot.copy(version = NearbyPlacesSnapshot.CURRENT_VERSION)
        prefs.edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(current))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    private companion object {
        const val PREFS_NAME = "nearby_cache"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
