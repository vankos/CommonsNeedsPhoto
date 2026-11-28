package com.example.commonsneedsphoto.data.store

import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class IgnoredPlacesTest {

    @Test
    fun `ignoring a place stores its qid and label once`() {
        val ignored = IgnoredPlaces()
            .add(place("Q1", "First"))
            .add(place("Q1", "First"))
        assertEquals(listOf(IgnoredPlace("Q1", "First")), ignored.places)
    }

    @Test
    fun `visible drops excluded places and keeps the order`() {
        val ignored = IgnoredPlaces().add(place("Q2", "Second"))
        val visible = ignored.visible(
            listOf(place("Q1", "First"), place("Q2", "Second"), place("Q3", "Third")),
        )
        assertEquals(listOf("Q1", "Q3"), visible.map { it.qid })
    }

    @Test
    fun `removing a place brings it back`() {
        val ignored = IgnoredPlaces().add(place("Q1", "First")).remove("Q1")
        assertEquals(emptyList<IgnoredPlace>(), ignored.places)
        assertEquals(
            listOf("Q1"),
            ignored.visible(listOf(place("Q1", "First"))).map { it.qid },
        )
    }

    @Test
    fun `excluded places survive a json round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = IgnoredPlaces()
            .add(place("Q1", "First"))
            .add(place("Q2", "Second"))
        val restored = json.decodeFromString<IgnoredPlaces>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    private fun place(qid: String, label: String) = NearbyPlace(
        qid = qid,
        label = label,
        description = null,
        latitude = 44.4268,
        longitude = 26.1025,
        instanceOf = null,
        commonsCategory = null,
    )
}
