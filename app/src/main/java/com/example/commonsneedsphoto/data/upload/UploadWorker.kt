package com.example.commonsneedsphoto.data.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.commonsneedsphoto.CommonsNeedsPhotoApp
import com.example.commonsneedsphoto.data.network.CommonsApiException
import com.example.commonsneedsphoto.data.network.UploadedFile
import com.example.commonsneedsphoto.data.network.WikidataApiException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uploads one photo in the background.
 *
 * The worker:
 * * runs only with a network connection, with exponential backoff between retries;
 * * shows a data-sync foreground notification with the chunk progress;
 * * resumes a chunked stash upload through [ChunkedUploader];
 * * publishes the file and adds its Wikidata P18 claim (best effort);
 * * reports the uploaded name, page URL, P18 status or a classified error to the sheet.
 */
class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val notifications by lazy { UploadNotifications(applicationContext) }

    override suspend fun doWork(): Result {
        val app = applicationContext as? CommonsNeedsPhotoApp
            ?: return Result.failure(errorData(UploadWork.ERROR_KIND_UPLOAD, "The app container is unavailable."))

        val filePath = inputData.getString(UploadWork.KEY_FILE_PATH)
            ?: return Result.failure(errorData(UploadWork.ERROR_KIND_UPLOAD, "The photo path is missing."))
        val fileName = inputData.getString(UploadWork.KEY_FILE_NAME).orEmpty()
        val wikitext = inputData.getString(UploadWork.KEY_WIKITEXT).orEmpty()
        val comment = inputData.getString(UploadWork.KEY_COMMENT).orEmpty()
        val qid = inputData.getString(UploadWork.KEY_QID).orEmpty()
        val resumeKey = inputData.getString(UploadWork.KEY_RESUME_KEY).orEmpty()
        val placeLabel = inputData.getString(UploadWork.KEY_PLACE_LABEL).orEmpty()
        val file = File(filePath)

        val lastPercent = AtomicInteger(-1)
        return try {
            notifications.ensureChannel()
            setForeground(notifications.foregroundInfo(progress = 0, placeLabel = placeLabel))
            setProgress(progressData(progress = 0, phase = UploadWork.PHASE_UPLOAD))

            val published = if (resumeKey.isNotBlank()) {
                app.container.uploadResumeStore.loadPublished(resumeKey)
            } else {
                null
            }
            val uploaded = if (published != null) {
                // A previous attempt published the file but was stopped before P18.
                UploadedFile(
                    fileName = published.fileName,
                    pageUrl = published.pageUrl,
                )
            } else {
                app.container.chunkedUploader.upload(
                    file = file,
                    fileName = fileName,
                    wikitext = wikitext,
                    comment = comment,
                    resumeKey = resumeKey,
                ) { uploadedBytes, totalBytes ->
                    val percent = if (totalBytes <= 0L) {
                        0
                    } else {
                        ((uploadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    }
                    if (lastPercent.getAndSet(percent) != percent) {
                        setProgressAsync(progressData(percent, UploadWork.PHASE_UPLOAD))
                        notifications.updateProgress(percent, placeLabel, UploadWork.PHASE_UPLOAD)
                    }
                }
            }

            if (published == null) {
                setProgress(progressData(100, UploadWork.PHASE_PUBLISH))
                notifications.updateProgress(100, placeLabel, UploadWork.PHASE_PUBLISH)
            }

            setProgress(
                progressData(
                    progress = 100,
                    phase = UploadWork.PHASE_WIKIDATA,
                    uploadedName = uploaded.fileName,
                    pageUrl = uploaded.pageUrl,
                ),
            )
            notifications.updateProgress(100, placeLabel, UploadWork.PHASE_WIKIDATA)

            var p18Updated = qid.isBlank()
            var p18ErrorCode: String? = null
            var p18Error: String? = null
            if (qid.isNotBlank()) {
                try {
                    p18Updated = app.container.wikidataApi.addImageClaim(qid, uploaded.fileName)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (exception: Exception) {
                    p18ErrorCode = (exception as? WikidataApiException)?.code
                    p18Error = exception.message ?: exception.javaClass.simpleName
                }
            }

            runCatching { file.delete() }
            notifications.showResult(
                success = true,
                fileName = uploaded.fileName,
                p18Failed = qid.isNotBlank() && !p18Updated,
            )
            Result.success(
                workDataOf(
                    UploadWork.KEY_UPLOADED_FILE_NAME to uploaded.fileName,
                    UploadWork.KEY_PAGE_URL to uploaded.pageUrl,
                    UploadWork.KEY_P18_UPDATED to p18Updated,
                    UploadWork.KEY_P18_ERROR_CODE to p18ErrorCode,
                    UploadWork.KEY_P18_ERROR to p18Error,
                ),
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (exception: Exception) {
            val code = (exception as? CommonsApiException)?.code
            val sessionExpired = UploadErrors.isSessionExpired(code)
            if (sessionExpired) {
                app.container.session.clear()
            }
            val retry = !sessionExpired &&
                UploadErrors.isRetryable(exception) &&
                runAttemptCount < MAX_RETRIES
            if (retry) {
                notifications.updateWaiting(placeLabel)
                return Result.retry()
            }

            val kind = when {
                sessionExpired -> UploadWork.ERROR_KIND_SESSION
                UploadErrors.isRetryable(exception) -> UploadWork.ERROR_KIND_NETWORK
                else -> UploadWork.ERROR_KIND_UPLOAD
            }
            val message = exception.message ?: exception.javaClass.simpleName
            notifications.showResult(
                success = false,
                fileName = fileName,
                sessionExpired = sessionExpired,
                error = message,
            )
            Result.failure(errorData(kind, message))
        }
    }

    override suspend fun getForegroundInfo() =
        notifications.foregroundInfo(
            progress = 0,
            placeLabel = inputData.getString(UploadWork.KEY_PLACE_LABEL).orEmpty(),
        )

    private fun progressData(
        progress: Int,
        phase: String,
        uploadedName: String? = null,
        pageUrl: String? = null,
    ) = Data.Builder()
        .putInt(UploadWork.KEY_PROGRESS, progress)
        .putString(UploadWork.KEY_PHASE, phase)
        .apply {
            if (uploadedName != null) putString(UploadWork.KEY_UPLOADED_FILE_NAME, uploadedName)
            if (pageUrl != null) putString(UploadWork.KEY_PAGE_URL, pageUrl)
        }
        .build()

    private fun errorData(kind: String, message: String) = workDataOf(
        UploadWork.KEY_ERROR_KIND to kind,
        UploadWork.KEY_ERROR to message,
    )

    private companion object {
        /** Total attempts = 1 + MAX_RETRIES. Exponential backoff starts at 30 seconds. */
        const val MAX_RETRIES = 3
    }
}
