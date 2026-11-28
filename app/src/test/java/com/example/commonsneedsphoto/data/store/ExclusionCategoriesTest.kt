package com.example.commonsneedsphoto.data.store

import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionCategoriesTest {

    @Test
    fun `defaults match the StuffAround app`() {
        assertEquals(
            listOf(
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
            ),
            ExclusionCategories.DEFAULT_CATEGORIES,
        )
    }

    @Test
    fun `defaults hide their types and keep the other places`() {
        val categories = ExclusionCategories(ExclusionCategories.DEFAULT_CATEGORIES)
        val places = listOf(
            place("Q1", "hotel"),
            place("Q2", "church"),
            place("Q3", "quarter"),
        )
        assertEquals(listOf("Q2"), categories.visible(places).map { it.qid })
    }

    @Test
    fun `a category matches one of several instance of labels`() {
        val categories = ExclusionCategories(listOf("hotel"))
        assertTrue(categories.excludes(place("Q1", "building, hotel, restaurant")))
        assertFalse(categories.excludes(place("Q2", "building, church")))
    }

    @Test
    fun `places without an instance of label stay visible`() {
        val categories = ExclusionCategories(listOf("hotel"))
        assertFalse(categories.excludes(place("Q1", null)))
    }

    @Test
    fun `adding a category trims and lower-cases it`() {
        val categories = ExclusionCategories().add("  Guest House  ")
        assertEquals(listOf("guest house"), categories.categories)
        assertTrue(categories.excludes(place("Q1", "guest house")))
    }

    @Test
    fun `blank and duplicate categories are ignored`() {
        val categories = ExclusionCategories()
            .add("hotel")
            .add("HOTEL")
            .add("   ")
        assertEquals(listOf("hotel"), categories.categories)
    }

    @Test
    fun `removing a category shows its places again`() {
        val categories = ExclusionCategories(listOf("hotel", "quarter")).remove("Hotel")
        assertEquals(listOf("quarter"), categories.categories)
        assertEquals(
            listOf("Q1"),
            categories.visible(listOf(place("Q1", "hotel"))).map { it.qid },
        )
    }

    @Test
    fun `categories survive a json round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = ExclusionCategories(listOf("hotel", "quarter"))
        val restored = json.decodeFromString<ExclusionCategories>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `normalized drops blanks and duplicates from stored values`() {
        val categories = ExclusionCategories(listOf(" Hotel ", "hotel", "", "Quarter"))
            .normalized()
        assertEquals(listOf("hotel", "quarter"), categories.categories)
    }

    private fun place(qid: String, instanceOf: String?) = NearbyPlace(
        qid = qid,
        label = qid,
        description = null,
        latitude = 44.4268,
        longitude = 26.1025,
        instanceOf = instanceOf,
        commonsCategory = null,
    )
}
