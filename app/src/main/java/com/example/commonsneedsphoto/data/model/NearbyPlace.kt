package com.example.commonsneedsphoto.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A Wikidata item that has coordinates but no image (P18) on Wikimedia Commons.
 */
@Serializable
data class NearbyPlace(
    val qid: String,
    val label: String,
    val description: String?,
    val latitude: Double,
    val longitude: Double,
    val instanceOf: String?,
    val commonsCategory: String?,
    /**
     * English labels of the [instanceOf] types' direct `P279` parents and
     * grandparents, comma separated. Used only by the exclusion categories,
     * never shown, and kept in the saved snapshot so changing a category still
     * needs no new search.
     */
    val instanceOfAncestors: String? = null,
    /**
     * QIDs of the direct `P31` types, filled by the nearby query so their
     * ancestors can be resolved in one batch. This is search-time data only and
     * is not written to the saved snapshot.
     */
    @Transient
    val typeQids: List<String> = emptyList(),
) {
    val wikidataUrl: String
        get() = "https://www.wikidata.org/wiki/$qid"

    fun distanceMeters(latitude: Double, longitude: Double): Double =
        distanceMeters(this.latitude, this.longitude, latitude, longitude)
}

@Serializable
data class LatLon(val latitude: Double, val longitude: Double)
