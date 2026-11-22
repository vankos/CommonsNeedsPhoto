package com.example.commonsneedsphoto.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsUploadResponseTest {

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `stash Continue returns the file key and the offset reported by Commons`() {
        val root = parse(
            """{"upload":{"result":"Continue","filekey":"abc123","offset":1048576}}""",
        )

        val result = parseStashResponse(root, previousOffset = 524288, sentLength = 524288)

        assertEquals("abc123", result.fileKey)
        assertEquals(1048576L, result.offset)
        assertFalse(result.completed)
    }

    @Test
    fun `a single chunk Success marks the file as fully stashed`() {
        val root = parse("""{"upload":{"result":"Success","filekey":"whole-file"}}""")

        val result = parseStashResponse(root, previousOffset = 0, sentLength = 4000)

        assertEquals("whole-file", result.fileKey)
        assertEquals(4000L, result.offset)
        assertTrue(result.completed)
    }

    @Test
    fun `stash error keeps the MediaWiki code for the resume reset`() {
        val root = parse(
            """{"error":{"code":"uploadstash-file-not-found","info":"The upload stash is gone"}}""",
        )

        val error = assertThrows(CommonsApiException::class.java) {
            parseStashResponse(root, previousOffset = 0, sentLength = 512)
        }

        assertEquals("uploadstash-file-not-found", error.code)
        assertEquals("The upload stash is gone", error.message)
    }

    @Test
    fun `Continue without a file key is rejected`() {
        val root = parse("""{"upload":{"result":"Continue","offset":512}}""")

        val error = assertThrows(CommonsApiException::class.java) {
            parseStashResponse(root, previousOffset = 0, sentLength = 512)
        }

        assertEquals("stashfailed", error.code)
    }

    @Test
    fun `published file name is normalised to the P18 title with spaces`() {
        val root = parse(
            """{"upload":{"result":"Success","filename":"Old_Tower_2026.jpg","imageinfo":{"descriptionurl":"https://commons.wikimedia.org/wiki/File:Old_Tower_2026.jpg"}}}""",
        )

        val uploaded = parsePublishResponse(root, fallbackName = "Fallback.jpg")

        assertEquals("Old Tower 2026.jpg", uploaded.fileName)
        assertEquals(
            "https://commons.wikimedia.org/wiki/File:Old_Tower_2026.jpg",
            uploaded.pageUrl,
        )
        assertEquals("Old Tower 2026.jpg", p18FileTitle("Old_Tower_2026.jpg"))
    }

    @Test
    fun `published file without imageinfo gets a Commons file page URL`() {
        val root = parse("""{"upload":{"result":"Success","filename":"Old_Tower.jpg"}}""")

        val uploaded = parsePublishResponse(root, fallbackName = "Fallback.jpg")

        assertEquals("Old Tower.jpg", uploaded.fileName)
        assertEquals(
            "https://commons.wikimedia.org/wiki/File:Old_Tower.jpg",
            uploaded.pageUrl,
        )
    }

    @Test
    fun `publish warning is reported as a warning`() {
        val root = parse(
            """{"upload":{"result":"Warning","warnings":{"exists":{"html":"A file with this name already exists."}}}}""",
        )

        val error = assertThrows(CommonsApiException::class.java) {
            parsePublishResponse(root, fallbackName = "Fallback.jpg")
        }

        assertEquals("warning", error.code)
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun `publish without an upload object is an upload failure`() {
        val root = parse("""{"error":{"code":"internal_api_error","info":"Server error"}}""")

        val error = assertThrows(CommonsApiException::class.java) {
            parsePublishResponse(root, fallbackName = "Fallback.jpg")
        }

        assertEquals("internal_api_error", error.code)
    }
}
