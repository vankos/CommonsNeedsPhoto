package com.example.commonsneedsphoto.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadResumeTest {

    private val now = 1_800_000_000_000L
    private val path = "/data/user/0/app/files/photos/upload_1.jpg"
    private val name = "Old Tower.jpg"
    private val chunk = UPLOAD_CHUNK_SIZE.toLong()
    private val size = chunk * 2
    private val modified = 1_700_000_000_000L

    private fun resume(
        offset: Long = chunk,
        size: Long = this.size,
        stashCreatedAt: Long = now - 60_000L,
        fileKey: String = "stash-key",
        finalName: String = name,
    ) = UploadResume(
        resumeKey = "Q1-test",
        filePath = path,
        fileName = name,
        finalName = finalName,
        fileSize = size,
        fileLastModified = modified,
        fileKey = fileKey,
        offset = offset,
        stashCreatedAt = stashCreatedAt,
        updatedAt = now,
    )

    @Test
    fun `a fresh state for the same aligned file is usable`() {
        assertTrue(resume().isUsable(path, name, size, modified, now))
    }

    @Test
    fun `a finished stash can still be published from the saved state`() {
        val finished = resume(offset = size)

        assertTrue(finished.isUsable(path, name, size, modified, now))
        assertTrue(finished.isComplete)
    }

    @Test
    fun `state older than the six hour stash lifetime is rejected`() {
        val stale = resume(stashCreatedAt = now - UPLOAD_STASH_MAX_AGE_MILLIS)

        assertFalse(stale.isUsable(path, name, size, modified, now))
        assertFalse(
            resume(stashCreatedAt = now - UPLOAD_STASH_MAX_AGE_MILLIS - 1)
                .isUsable(path, name, size, modified, now),
        )
    }

    @Test
    fun `state is rejected when any file attribute changed`() {
        val stored = resume()

        assertFalse(stored.isUsable("$path.other", name, size, modified, now))
        assertFalse(stored.isUsable(path, "Another name.jpg", size, modified, now))
        assertFalse(stored.isUsable(path, name, size + 1, modified, now))
        assertFalse(stored.isUsable(path, name, size, modified + 1, now))
    }

    @Test
    fun `state is rejected for a misaligned or out of range offset`() {
        assertFalse(resume(offset = 100L).isUsable(path, name, size, modified, now))
        assertFalse(resume(offset = size + 512L).isUsable(path, name, size, modified, now))
        assertFalse(resume(offset = -512L).isUsable(path, name, size, modified, now))
    }

    @Test
    fun `state without a stash key or final name is rejected`() {
        assertFalse(resume(fileKey = "").isUsable(path, name, size, modified, now))
        assertFalse(resume(finalName = "").isUsable(path, name, size, modified, now))
    }

    @Test
    fun `resume key is stable per file and QID`() {
        val file = File.createTempFile("commons-upload", ".jpg")
        try {
            file.writeBytes(ByteArray(16))
            val key = UploadResumeKey.of("Q123", file)

            assertEquals(key, UploadResumeKey.of("Q123", file))
            assertNotEquals(key, UploadResumeKey.of("Q456", file))
            assertTrue(key.matches(Regex("[A-Za-z0-9._-]+")))
        } finally {
            file.delete()
        }
    }
}
