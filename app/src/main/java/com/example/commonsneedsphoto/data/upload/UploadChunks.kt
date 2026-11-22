package com.example.commonsneedsphoto.data.upload

/** MediaWiki ``action=upload&stash=1`` chunk size used by the original Commons app. */
const val UPLOAD_CHUNK_SIZE = 512 * 1024

/**
 * MediaWiki keeps an upload stash for ``$wgUploadStashMaxAge`` (6 hours by default).
 * A resume state older than this cannot be continued.
 */
const val UPLOAD_STASH_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L

data class UploadChunk(
    val index: Int,
    val offset: Long,
    val length: Int,
)

/**
 * Splits a file into the chunks expected by MediaWiki's chunked stash upload.
 *
 * Pure Kotlin so the offset/plan logic can be unit tested without Android.
 */
data class UploadChunkPlan(
    val totalBytes: Long,
    val chunkSize: Int = UPLOAD_CHUNK_SIZE,
) {
    init {
        require(totalBytes >= 0) { "totalBytes must not be negative" }
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    val chunkCount: Int
        get() = if (totalBytes == 0L) 0 else (((totalBytes - 1) / chunkSize) + 1).toInt()

    fun offsetOf(index: Int): Long {
        require(index in 0 until chunkCount) { "Chunk $index is out of range" }
        return index.toLong() * chunkSize
    }

    fun lengthOf(index: Int): Int {
        require(index in 0 until chunkCount) { "Chunk $index is out of range" }
        return minOf(chunkSize.toLong(), totalBytes - offsetOf(index)).toInt()
    }

    /**
     * @return the chunk that starts at [offset], or null when the whole file is stashed.
     * A non-aligned offset is still returned here; callers should check [isAligned] first.
     */
    fun chunkAt(offset: Long): UploadChunk? {
        if (offset < 0 || offset >= totalBytes) return null
        return UploadChunk(
            index = (offset / chunkSize).toInt(),
            offset = offset,
            length = minOf(chunkSize.toLong(), totalBytes - offset).toInt(),
        )
    }

    fun isComplete(offset: Long): Boolean = offset >= totalBytes

    /** MediaWiki returns offsets on chunk boundaries, and the last one equals the file size. */
    fun isAligned(offset: Long): Boolean =
        offset == 0L || offset == totalBytes || offset % chunkSize == 0L
}
