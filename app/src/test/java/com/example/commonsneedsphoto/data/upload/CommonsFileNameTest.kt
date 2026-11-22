package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.model.NearbyPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CommonsFileNameTest {

    @Test
    fun `sanitize removes MediaWiki-invalid characters and forces jpg`() {
        assertEquals(
            "Old Tower 1.jpg",
            CommonsFileName.sanitize("File:Old/Tower: #1.JPG", "fallback"),
        )
    }

    @Test
    fun `sanitize keeps the given base name`() {
        assertEquals(
            "Mira Consulting Plus - 2026-11-22.jpg",
            CommonsFileName.sanitize("Mira Consulting Plus - 2026-11-22.jpg", "fallback"),
        )
    }

    @Test
    fun `sanitize falls back when the name becomes empty`() {
        assertEquals(
            "Q123 photo.jpg",
            CommonsFileName.sanitize("///", "Q123 photo"),
        )
    }

    @Test
    fun `withSuffix inserts a collision suffix before the extension`() {
        assertEquals("Old Tower (12345).jpg", CommonsFileName.withSuffix("Old Tower.jpg", "12345"))
        assertEquals("Old Tower (12345)", CommonsFileName.withSuffix("Old Tower", "12345"))
    }

    @Test
    fun `defaultName uses the label and upload date`() {
        val place = NearbyPlace(
            qid = "Q123",
            label = "Old Tower",
            description = null,
            latitude = 0.0,
            longitude = 0.0,
            instanceOf = null,
            commonsCategory = null,
        )
        val name = CommonsFileName.defaultName(place, LocalDate.of(2026, 11, 22))

        assertTrue(name.startsWith("Old Tower - 2026-11-22"))
        assertTrue(name.endsWith(".jpg"))
    }
}
