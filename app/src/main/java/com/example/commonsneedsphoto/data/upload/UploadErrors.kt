package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.network.CommonsApiException
import java.io.IOException

/**
 * Classifies MediaWiki upload failures for the background worker and the resume store.
 *
 * Pure Kotlin so the retry/reset decisions can be unit tested.
 */
object UploadErrors {

    /** The session or CSRF token is no longer valid; the user has to log in again. */
    val SESSION_CODES = setOf(
        "assertuserfailed",
        "mustbeloggedin",
        "notloggedin",
        "badtoken",
    )

    /**
     * Errors that retrying the same file with the same name can never fix.
     * Mirrors UploadBase::convertVerifyErrorToStatus() in MediaWiki.
     */
    val PERMANENT_CODES = setOf(
        "filename-tooshort",
        "illegal-filename",
        "filename-toolong",
        "filetype-missing",
        "windows-nonascii-filename",
        "empty-file",
        "file-too-large",
        "filetype-banned",
        "titleblacklist-forbidden",
    )

    /**
     * Stash errors that a fresh ``action=upload&stash=1`` attempt can recover from.
     * ``verification-error`` is included because a chunk damaged in transit causes it and
     * uploading the file again can succeed (the original app also clears its chunks then).
     */
    val RETRY_WITH_NEW_STASH_CODES = setOf(
        "uploadstash-file-not-found",
        "stashfailed",
        "verification-error",
        "chunk-too-small",
        "stash-offset",
    )

    /**
     * Errors after which the saved ``filekey``/offset cannot be continued and the state
     * must be cleared, so the next attempt starts a fresh stash.
     */
    val RESET_STASH_CODES = PERMANENT_CODES + RETRY_WITH_NEW_STASH_CODES + setOf("file-missing")

    fun isSessionExpired(code: String?): Boolean = code in SESSION_CODES

    fun isPermanent(code: String?): Boolean = code in PERMANENT_CODES

    fun shouldResetStash(code: String?): Boolean = code in RESET_STASH_CODES

    /**
     * True when running the same work again can succeed: a network failure, a server
     * problem (5xx) or too many requests. API and permanent file errors are not retried.
     */
    fun isRetryable(error: Throwable): Boolean {
        val apiError = error as? CommonsApiException
        if (apiError == null) {
            // Network failures are IOException. WorkManager also throws IllegalStateException
            // when a foreground service cannot start from the background; a later attempt
            // (for example after the user opens the app) can succeed.
            return error is IOException || error is IllegalStateException
        }
        if (isSessionExpired(apiError.code)) return false
        if (isPermanent(apiError.code)) return false
        val status = apiError.httpStatus
        if (status == HTTP_TOO_MANY_REQUESTS ||
            status in HTTP_SERVER_ERROR_FIRST..HTTP_SERVER_ERROR_LAST
        ) {
            return true
        }
        // A failed stash can be restarted from zero; other API errors (a warning, a
        // rejected publish, a missing file) need a change from the user.
        return apiError.code in RETRY_WITH_NEW_STASH_CODES
    }

    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR_FIRST = 500
    private const val HTTP_SERVER_ERROR_LAST = 599
}
