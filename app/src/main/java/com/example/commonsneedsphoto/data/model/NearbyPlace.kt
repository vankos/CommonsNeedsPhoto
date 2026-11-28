package com.example.commonsneedsphoto.data.model

import kotlinx.serialization.Serializable

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
) {
    val wikidataUrl: String
        get() = "https://www.wikidata.org/wiki/$qid"

    fun distanceMeters(latitude: Double, longitude: Double): Double =
        distanceMeters(this.latitude, this.longitude, latitude, longitude)
}

@Serializable
data class LatLon(val latitude: Double, val longitude: Double)
