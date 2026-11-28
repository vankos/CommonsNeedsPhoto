package com.example.commonsneedsphoto.data.store

import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPlacesSnapshotTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `points from the last day are usable`() {
        assertTrue(snapshot(now - 23 * HOUR_MILLIS).isUsable(now))
    }

    @Test
    fun `points older than a day are not usable`() {
        assertFalse(snapshot(now - NearbyPlacesSnapshot.TTL_MILLIS).isUsable(now))
        assertFalse(snapshot(now - 25 * HOUR_MILLIS).isUsable(now))
    }

    @Test
    fun `a device clock that moved backwards keeps the points`() {
        assertTrue(snapshot(now + HOUR_MILLIS).isUsable(now))
    }

    @Test
    fun `an empty result is not usable`() {
        assertFalse(snapshot(now).copy(places = emptyList()).isUsable(now))
    }

    @Test
    fun `snapshot survives a json round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = snapshot(now)
        val restored = json.decodeFromString<NearbyPlacesSnapshot>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    private fun snapshot(savedAtMillis: Long) = NearbyPlacesSnapshot(
        savedAtMillis = savedAtMillis,
        searchedCenter = LatLon(44.4268, 26.1025),
        radiusKm = 5.0,
        places = listOf(
            NearbyPlace(
                qid = "Q1",
                label = "Test place",
                description = "A place without a photo",
                latitude = 44.4268,
                longitude = 26.1025,
                instanceOf = "Q5",
                commonsCategory = "Test",
            ),
        ),
    )

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
    }
}
