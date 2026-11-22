package com.example.commonsneedsphoto.data.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Everything the worker needs to upload one photo and add its P18 claim. */
data class UploadSpec(
    val file: File,
    val fileName: String,
    val wikitext: String,
    val comment: String,
    val qid: String,
    val placeLabel: String,
    val resumeKey: String,
)

data class UploadWorkHandle(
    val uniqueName: String,
    val id: UUID,
)

/**
 * Builds and observes the WorkManager request for a photo upload.
 *
 * The request is unique per processed file: a retry from the sheet reuses the same name,
 * so WorkManager keeps a running upload and the resume state from [UploadResumeStore]
 * can continue the stash.
 */
object UploadWork {

    const val TAG = "commons-photo-upload"

    const val KEY_FILE_PATH = "file_path"
    const val KEY_FILE_NAME = "file_name"
    const val KEY_WIKITEXT = "wikitext"
    const val KEY_COMMENT = "comment"
    const val KEY_QID = "qid"
    const val KEY_PLACE_LABEL = "place_label"
    const val KEY_RESUME_KEY = "resume_key"

    const val KEY_PROGRESS = "progress"
    const val KEY_PHASE = "phase"
    const val KEY_UPLOADED_FILE_NAME = "uploaded_file_name"
    const val KEY_PAGE_URL = "page_url"
    const val KEY_P18_UPDATED = "p18_updated"
    const val KEY_P18_ERROR_CODE = "p18_error_code"
    const val KEY_P18_ERROR = "p18_error"
    const val KEY_ERROR = "error"
    const val KEY_ERROR_KIND = "error_kind"

    const val PHASE_UPLOAD = "upload"
    const val PHASE_PUBLISH = "publish"
    const val PHASE_WIKIDATA = "wikidata"

    const val ERROR_KIND_UPLOAD = "upload"
    const val ERROR_KIND_SESSION = "session"
    const val ERROR_KIND_NETWORK = "network"

    fun uniqueName(resumeKey: String): String = "commons-upload-$resumeKey"

    fun enqueue(context: Context, spec: UploadSpec): UploadWorkHandle {
        val workName = uniqueName(spec.resumeKey)
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    KEY_FILE_PATH to spec.file.absolutePath,
                    KEY_FILE_NAME to spec.fileName,
                    KEY_WIKITEXT to spec.wikitext,
                    KEY_COMMENT to spec.comment,
                    KEY_QID to spec.qid,
                    KEY_PLACE_LABEL to spec.placeLabel,
                    KEY_RESUME_KEY to spec.resumeKey,
                ),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        return UploadWorkHandle(uniqueName = workName, id = request.id)
    }

    fun observe(context: Context, id: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(id)

    fun cancel(context: Context, uniqueName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName)
    }

    private const val BACKOFF_SECONDS = 30L
}
