package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.network.CommonsApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UploadErrorsTest {

    @Test
    fun `session errors require a new login`() {
        assertTrue(UploadErrors.isSessionExpired("assertuserfailed"))
        assertTrue(UploadErrors.isSessionExpired("mustbeloggedin"))
        assertTrue(UploadErrors.isSessionExpired("notloggedin"))
        assertTrue(UploadErrors.isSessionExpired("badtoken"))
        assertFalse(UploadErrors.isSessionExpired(null))
        assertFalse(UploadErrors.isSessionExpired("upload-failed"))
    }

    @Test
    fun `network failures and server errors are retried`() {
        assertTrue(UploadErrors.isRetryable(IOException("offline")))
        assertTrue(UploadErrors.isRetryable(IllegalStateException("foreground service denied")))
        assertTrue(UploadErrors.isRetryable(CommonsApiException("http-503", "down", httpStatus = 503)))
        assertTrue(UploadErrors.isRetryable(CommonsApiException("http-429", "slow down", httpStatus = 429)))
        assertTrue(UploadErrors.isRetryable(CommonsApiException("stashfailed", "bad chunk")))
    }

    @Test
    fun `permanent upload and session errors are not retried`() {
        assertFalse(UploadErrors.isRetryable(CommonsApiException("file-too-large", "too large")))
        assertFalse(
            UploadErrors.isRetryable(
                CommonsApiException("upload-failed", "rejected", httpStatus = 400),
            ),
        )
        assertFalse(UploadErrors.isRetryable(CommonsApiException("assertuserfailed", "expired")))
    }

    @Test
    fun `stash errors clear the resume state`() {
        assertTrue(UploadErrors.shouldResetStash("uploadstash-file-not-found"))
        assertTrue(UploadErrors.shouldResetStash("verification-error"))
        assertTrue(UploadErrors.shouldResetStash("chunk-too-small"))
        assertTrue(UploadErrors.shouldResetStash("file-too-large"))
        assertFalse(UploadErrors.shouldResetStash("upload-failed"))
    }

    @Test
    fun `a publish warning is not a reset or retry`() {
        val warning = CommonsApiException("warning", "file exists")

        assertFalse(UploadErrors.shouldResetStash(warning.code))
        assertFalse(UploadErrors.isRetryable(warning))
    }
}
