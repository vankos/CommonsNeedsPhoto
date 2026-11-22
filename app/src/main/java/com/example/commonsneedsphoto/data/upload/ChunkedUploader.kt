package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.network.CommonsApi
import com.example.commonsneedsphoto.data.network.CommonsApiException
import com.example.commonsneedsphoto.data.network.UploadedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chunked, resumable upload to Wikimedia Commons.
 *
 * 1. Checks the file name once and remembers the unique name.
 * 2. Uploads 512 KB chunks to the MediaWiki upload stash, persisting the returned
 *    ``filekey`` and byte offset after every chunk.
 * 3. Publishes the completed stash as a file page.
 *
 * If the worker is stopped (network loss, process death, WorkManager retry), the next
 * attempt finds the persisted position and continues instead of starting from zero.
 * Resume state is cleared on success and after errors that make the stash unusable.
 */
class ChunkedUploader(
    private val api: CommonsApi,
    private val resumeStore: UploadResumeStore,
) {
    suspend fun upload(
        file: File,
        fileName: String,
        wikitext: String,
        comment: String,
        resumeKey: String,
        onProgress: (uploaded: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadedFile = withContext(Dispatchers.IO) {
        if (!file.isFile) {
            resumeStore.clear(resumeKey)
            throw CommonsApiException("file-missing", "The photo file is no longer available. Choose it again.")
        }
        val totalBytes = file.length()
        if (totalBytes <= 0L) {
            resumeStore.clear(resumeKey)
            throw CommonsApiException("empty-file", "The photo file is empty.")
        }

        val plan = UploadChunkPlan(totalBytes)
        val now = System.currentTimeMillis()
        val stored = resumeStore.load(resumeKey)
        val resume = stored?.takeIf { it.isUsableFor(file, fileName, now) }
        if (stored != null && resume == null) {
            resumeStore.clear(resumeKey)
        }

        val token = api.csrfToken()
        val finalName = resume?.finalName?.takeIf { it.isNotBlank() }
            ?: api.uniqueFileName(fileName)
        var fileKey: String? = resume?.fileKey
        var offset = resume?.offset ?: 0L
        val stashCreatedAt = resume?.stashCreatedAt ?: now

        try {
            while (!plan.isComplete(offset)) {
                if (!plan.isAligned(offset)) {
                    throw CommonsApiException(
                        "stash-offset",
                        "Commons returned an unexpected upload offset",
                    )
                }
                val chunk = plan.chunkAt(offset)
                    ?: throw CommonsApiException("stash-offset", "The upload offset is past the file end")

                val result = api.stashChunk(
                    file = file,
                    fileName = fileName,
                    offset = chunk.offset,
                    length = chunk.length,
                    fileKey = fileKey,
                    token = token,
                    onProgress = onProgress,
                )
                if (result.offset <= offset) {
                    throw CommonsApiException(
                        "stash-offset",
                        "Commons did not accept the upload chunk",
                    )
                }
                if (result.offset > totalBytes) {
                    throw CommonsApiException(
                        "stash-offset",
                        "Commons reported more bytes than the file contains",
                    )
                }
                fileKey = result.fileKey
                offset = result.offset
                resumeStore.save(
                    UploadResume(
                        resumeKey = resumeKey,
                        filePath = file.absolutePath,
                        fileName = fileName,
                        finalName = finalName,
                        fileSize = totalBytes,
                        fileLastModified = file.lastModified(),
                        fileKey = result.fileKey,
                        offset = offset,
                        stashCreatedAt = stashCreatedAt,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                onProgress(offset, totalBytes)
                if (result.completed) break
            }

            val completedKey = fileKey
                ?: throw CommonsApiException("stashfailed", "Commons did not return a stash file key")
            onProgress(totalBytes, totalBytes)
            val uploaded = api.publishFromStash(
                fileName = finalName,
                wikitext = wikitext,
                comment = comment,
                fileKey = completedKey,
                token = token,
            )
            // Keep the result until P18 is done, so a retry does not upload a duplicate file.
            resumeStore.savePublished(
                PublishedUpload(
                    resumeKey = resumeKey,
                    fileName = uploaded.fileName,
                    pageUrl = uploaded.pageUrl,
                ),
            )
            resumeStore.clear(resumeKey)
            uploaded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (exception: CommonsApiException) {
            if (UploadErrors.shouldResetStash(exception.code)) {
                resumeStore.clear(resumeKey)
            }
            throw exception
        }
    }
}
