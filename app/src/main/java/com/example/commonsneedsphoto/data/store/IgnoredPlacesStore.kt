package com.example.commonsneedsphoto.data.store

import android.content.Context
import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A place the user has excluded from the map. The label is kept next to the QID
 * so the settings list can show the point after the nearby cache has expired.
 */
@Serializable
data class IgnoredPlace(
    val qid: String,
    val label: String,
)

/**
 * Places hidden from the map, kept separately from the nearby cache: the cache
 * still holds them, so restoring one brings it back without a new query.
 */
@Serializable
data class IgnoredPlaces(
    val places: List<IgnoredPlace> = emptyList(),
) {
    /** Adds [place] unless its QID is already excluded. */
    fun add(place: NearbyPlace): IgnoredPlaces = if (places.any { it.qid == place.qid }) {
        this
    } else {
        copy(places = places + IgnoredPlace(place.qid, place.label))
    }

    fun remove(qid: String): IgnoredPlaces =
        copy(places = places.filterNot { it.qid == qid })

    /** The part of [places] that is not excluded, in the original order. */
    fun visible(places: List<NearbyPlace>): List<NearbyPlace> {
        val ignored = this.places.mapTo(HashSet()) { it.qid }
        return places.filterNot { it.qid in ignored }
    }
}

/**
 * Persists the excluded places in an app-private preference file.
 *
 * An unreadable value is treated as an empty list: a broken file must neither
 * hide every place nor crash the map.
 */
class IgnoredPlacesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): IgnoredPlaces {
        val raw = prefs.getString(KEY_PLACES, null) ?: return IgnoredPlaces()
        return runCatching {
            json.decodeFromString<IgnoredPlaces>(raw)
        }.getOrDefault(IgnoredPlaces())
    }

    fun save(value: IgnoredPlaces) {
        prefs.edit()
            .putString(KEY_PLACES, json.encodeToString(value))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "ignored_places"
        const val KEY_PLACES = "places"
    }
}
