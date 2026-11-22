package com.example.commonsneedsphoto.data.upload

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Saved position of a chunked stash upload.
 *
 * MediaWiki returns a ``filekey`` after the first chunk and the byte [offset] it has
 * accepted after every chunk. Keeping both lets a WorkManager retry (or a new process)
 * continue where the previous attempt stopped instead of uploading the file again.
 *
 * The state is only usable for the exact same file and only while the stash lives
 * (see [UPLOAD_STASH_MAX_AGE_MILLIS]).
 */
@Serializable
data class UploadResume(
    val resumeKey: String,
    val filePath: String,
    val fileName: String,
    val finalName: String,
    val fileSize: Long,
    val fileLastModified: Long,
    val fileKey: String,
    val offset: Long,
    val stashCreatedAt: Long,
    val updatedAt: Long,
) {
    fun isUsable(
        filePath: String,
        fileName: String,
        fileSize: Long,
        fileLastModified: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (this.filePath != filePath) return false
        if (this.fileName != fileName) return false
        if (this.fileSize != fileSize) return false
        if (this.fileLastModified != fileLastModified) return false
        if (fileKey.isBlank() || finalName.isBlank()) return false
        if (fileSize <= 0L) return false
        if (offset < 0L || offset > fileSize) return false
        if (!UploadChunkPlan(fileSize).isAligned(offset)) return false
        // A negative age means the device clock moved backwards; the stash may still be alive.
        return now - stashCreatedAt < UPLOAD_STASH_MAX_AGE_MILLIS
    }

    fun isUsableFor(file: File, fileName: String, now: Long = System.currentTimeMillis()): Boolean =
        isUsable(
            filePath = file.absolutePath,
            fileName = fileName,
            fileSize = file.length(),
            fileLastModified = file.lastModified(),
            now = now,
        )

    val isComplete: Boolean
        get() = offset >= fileSize
}

/**
 * Stable key for one upload attempt: the same processed photo and the same requested
 * file name reuse the same resume entry (and therefore the same WorkManager work).
 */
object UploadResumeKey {
    fun of(qid: String, file: File): String = buildString {
        append(qid)
        append('-')
        append(file.name)
        append('-')
        append(file.length())
        append('-')
        append(file.lastModified())
    }.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * A file that was already published to Commons but whose P18 edit has not finished yet.
 *
 * Saving this before the Wikidata step lets a WorkManager retry skip the upload and go
 * straight back to P18, instead of creating a duplicate Commons file.
 */
@Serializable
data class PublishedUpload(
    val resumeKey: String,
    val fileName: String,
    val pageUrl: String,
)

/**
 * Persists [UploadResume] entries in an app-private preference file.
 *
 * Writes use ``commit`` because a worker can be killed right after a chunk was accepted;
 * losing that write would only re-upload one chunk, but a synchronous write keeps the
 * persisted offset as close to the server as possible.
 */
class UploadResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(resumeKey: String): UploadResume? {
        val raw = prefs.getString(prefKey(resumeKey), null) ?: return null
        return runCatching {
            json.decodeFromString<UploadResume>(raw).takeIf { it.resumeKey == resumeKey }
        }.getOrNull()
    }

    fun save(resume: UploadResume) {
        prefs.edit()
            .putString(prefKey(resume.resumeKey), json.encodeToString(resume))
            .commit()
    }

    fun clear(resumeKey: String) {
        prefs.edit().remove(prefKey(resumeKey)).commit()
    }

    fun loadPublished(resumeKey: String): PublishedUpload? {
        val raw = prefs.getString(publishedKey(resumeKey), null) ?: return null
        return runCatching {
            json.decodeFromString<PublishedUpload>(raw).takeIf { it.resumeKey == resumeKey }
        }.getOrNull()
    }

    fun savePublished(published: PublishedUpload) {
        prefs.edit()
            .putString(publishedKey(published.resumeKey), json.encodeToString(published))
            .commit()
    }

    fun clearPublished(resumeKey: String) {
        prefs.edit().remove(publishedKey(resumeKey)).commit()
    }

    private fun prefKey(resumeKey: String): String = PREFIX + resumeKey

    private fun publishedKey(resumeKey: String): String = PUBLISHED_PREFIX + resumeKey

    private companion object {
        const val PREFS_NAME = "upload_resume"
        const val PREFIX = "resume_"
        const val PUBLISHED_PREFIX = "published_"
    }
}
