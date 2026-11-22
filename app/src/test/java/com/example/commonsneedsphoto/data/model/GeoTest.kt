package com.example.commonsneedsphoto.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `distance between neighbouring points is about 1111 meters`() {
        val distance = distanceMeters(44.4268, 26.1025, 44.4368, 26.1025)
        assertEquals(1112.0, distance, 5.0)
    }

    @Test
    fun `distance to itself is zero`() {
        assertEquals(0.0, distanceMeters(44.4268, 26.1025, 44.4268, 26.1025), 0.000001)
    }

    @Test
    fun `place exposes distance to a location`() {
        val place = NearbyPlace(
            qid = "Q1",
            label = "Test",
            description = null,
            latitude = 44.4268,
            longitude = 26.1025,
            instanceOf = null,
            commonsCategory = null,
        )
        assertEquals(500.0, place.distanceMeters(44.4313, 26.1025), 5.0)
    }
}
