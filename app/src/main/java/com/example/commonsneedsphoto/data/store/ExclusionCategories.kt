package com.example.commonsneedsphoto.data.store

import android.content.Context
import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wikidata type labels ("instance of") whose places are left out of the map and
 * search results. Values are kept lower case and in English, the language the
 * nearby query asks the type labels in, so [NearbyPlace.instanceOf] and
 * [NearbyPlace.instanceOfAncestors] can be compared with them directly.
 *
 * The defaults are the ones the neighbouring StuffAround app ships with: a photo
 * of a hotel room or of a whole quarter is not what the needs-photo list is for.
 */
@Serializable
data class ExclusionCategories(
    val categories: List<String> = emptyList(),
) {
    /** Adds [value] unless it is blank or already present. */
    fun add(value: String): ExclusionCategories {
        val normalized = normalize(value)
        return if (normalized.isEmpty() || normalized in categories) {
            this
        } else {
            copy(categories = categories + normalized)
        }
    }

    fun remove(value: String): ExclusionCategories {
        val normalized = normalize(value)
        return copy(categories = categories.filterNot { it == normalized })
    }

    /**
     * True when [place] is an instance of at least one excluded category, either
     * directly or through a `P279` ancestor of one of its types.
     */
    fun excludes(place: NearbyPlace): Boolean {
        if (categories.isEmpty()) return false
        return matches(place.instanceOf) || matches(place.instanceOfAncestors)
    }

    private fun matches(labels: String?): Boolean =
        labels
            ?.split(',')
            ?.map(::normalize)
            ?.any(categories::contains)
            ?: false

    /** The part of [places] that is not excluded by a category, in the original order. */
    fun visible(places: List<NearbyPlace>): List<NearbyPlace> =
        if (categories.isEmpty()) places else places.filterNot(::excludes)

    /** Trims and lower-cases every value, removes blanks and duplicates. */
    fun normalized(): ExclusionCategories = copy(
        categories = categories
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .distinct(),
    )

    companion object {
        /** The default exclusion categories of the StuffAround app. */
        val DEFAULT_CATEGORIES = listOf(
            "hotel",
            "hostel",
            "guest house",
            "apartment",
            "neighborhood",
            "quarter",
            "mahalle",
            "battle",
            "ancient city",
            "siege",
        )

        fun normalize(value: String): String = value.trim().lowercase()
    }
}

/**
 * Persists the exclusion categories in an app-private preference file.
 *
 * A missing value means the app has never saved a list, so [ExclusionCategories.DEFAULT_CATEGORIES]
 * apply; an explicitly empty stored list stays empty, so removing every category is respected.
 * An unreadable value also falls back to the defaults: a broken file must neither
 * hide every place nor crash the map.
 */
class ExclusionCategoriesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ExclusionCategories {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return defaults()
        return runCatching {
            json.decodeFromString<ExclusionCategories>(raw).normalized()
        }.getOrDefault(defaults())
    }

    fun save(value: ExclusionCategories) {
        prefs.edit()
            .putString(KEY_CATEGORIES, json.encodeToString(value.normalized()))
            .apply()
    }

    private fun defaults() = ExclusionCategories(ExclusionCategories.DEFAULT_CATEGORIES)

    private companion object {
        const val PREFS_NAME = "exclusion_categories"
        const val KEY_CATEGORIES = "categories"
    }
}
