package com.example.commonsneedsphoto.data.network

import com.example.commonsneedsphoto.data.upload.CommonsFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import kotlin.random.Random

class CommonsApiException(
    val code: String,
    message: String,
    /** HTTP status when the failure is an HTTP error, null for API-level errors. */
    val httpStatus: Int? = null,
) : IOException(message)

data class UploadedFile(
    /** File title with spaces, as Wikidata P18 expects it. */
    val fileName: String,
    val pageUrl: String,
)

/**
 * Result of one ``action=upload&stash=1`` request.
 *
 * @property fileKey stash key returned by MediaWiki
 * @property offset total number of bytes MediaWiki has accepted so far
 * @property completed true when MediaWiki already has the whole file in the stash
 */
data class StashChunkResult(
    val fileKey: String,
    val offset: Long,
    val completed: Boolean,
)

/**
 * Wikimedia Commons client for the MVP upload flow:
 * bot-password login -> CSRF token -> chunked stash upload -> publish from stash.
 *
 * The protocol mirrors the original Commons Android app: 512 KB chunks are sent with
 * ``action=upload&stash=1&ignorewarnings=1`` and the returned ``filekey``/``offset`` are
 * reused for the next chunk. The finished stash is published with a normal
 * ``action=upload`` form request.
 */
class CommonsApi(
    private val session: CommonsSession,
    baseClient: OkHttpClient = Network.client,
) {
    private val client = baseClient.newBuilder()
        .cookieJar(session)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(loginName: String, password: String): String = withContext(Dispatchers.IO) {
        if (loginName.trim().contains('@')) {
            loginWithBotPassword(loginName, password)
        } else {
            loginWithAccount(loginName, password)
        }
    }

    private fun loginWithAccount(loginName: String, password: String): String {
        val token = fetchToken("login")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "clientlogin")
            .addFormDataPart("format", "json")
            .addFormDataPart("formatversion", "2")
            .addFormDataPart("username", loginName.trim())
            .addFormDataPart("password", password)
            .addFormDataPart("logintoken", token)
            .addFormDataPart("loginreturnurl", "https://commons.wikimedia.org/")
            .addFormDataPart("uselang", "en")
            .build()
        val root = execute(body)
        val clientlogin = root["clientlogin"]?.jsonObject
            ?: throw CommonsApiException("login-failed", root.errorText() ?: "Login failed")
        return when (clientlogin.string("status")) {
            "PASS" -> {
                val name = clientlogin.string("username") ?: loginName.trim()
                session.userName = name
                name
            }
            "UI" -> throw CommonsApiException(
                "login-verification",
                "This account requires additional verification (two-factor authentication or a CAPTCHA). " +
                    "Please create a bot password and log in as name@bot instead.",
            )
            else -> throw CommonsApiException(
                "login-failed",
                clientlogin.string("reason")
                    ?: clientlogin.string("message")
                    ?: "Wrong username or password",
            )
        }
    }

    private fun loginWithBotPassword(loginName: String, password: String): String {
        val token = fetchToken("login")
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "login")
            .addFormDataPart("format", "json")
            .addFormDataPart("formatversion", "2")
            .addFormDataPart("lgname", loginName.trim())
            .addFormDataPart("lgpassword", password)
            .addFormDataPart("lgtoken", token)
            .build()
        val root = execute(body)
        val login = root["login"]?.jsonObject
            ?: throw CommonsApiException("login-failed", root.errorText() ?: "Login failed")
        return when (login.string("result")) {
            "Success" -> {
                val name = login.string("lgusername") ?: loginName.substringBefore('@')
                session.userName = name
                name
            }
            else -> throw CommonsApiException(
                "login-failed",
                login.string("reason")
                    ?: login.string("message")
                    ?: "Wrong username or bot password",
            )
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            val token = fetchToken("csrf")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("action", "logout")
                .addFormDataPart("format", "json")
                .addFormDataPart("token", token)
                .build()
            execute(body)
        }
        session.clear()
    }

    /** Fetches a fresh CSRF token; a session-expired error here is reported as an API error. */
    suspend fun csrfToken(): String = withContext(Dispatchers.IO) {
        fetchToken("csrf")
    }

    /**
     * Checks Commons and appends a random suffix when the name already exists, so an
     * upload never overwrites somebody else's file.
     */
    suspend fun uniqueFileName(fileName: String): String = withContext(Dispatchers.IO) {
        var candidate = fileName
        repeat(5) {
            if (!fileExists(candidate)) return@withContext candidate
            candidate = CommonsFileName.withSuffix(
                fileName,
                Random.nextInt(10000, 100000).toString(),
            )
        }
        throw CommonsApiException(
            "file-name",
            "Could not find a free file name on Commons. Please change the name and try again.",
        )
    }

    /**
     * Sends one chunk of [file] to the MediaWiki upload stash.
     *
     * @param offset byte offset the chunk starts at (the offset returned by the previous call)
     * @param length number of bytes to send
     * @param fileKey key returned for the previous chunk, null for the first chunk
     */
    suspend fun stashChunk(
        file: File,
        fileName: String,
        offset: Long,
        length: Int,
        fileKey: String?,
        token: String,
        onProgress: (uploaded: Long, total: Long) -> Unit,
    ): StashChunkResult = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset must not be negative" }
        require(length > 0) { "chunk length must be positive" }
        val totalSize = file.length()
        val requestBody = ChunkRequestBody(
            file = file,
            offset = offset,
            length = length.toLong(),
            totalSize = totalSize,
            onProgress = onProgress,
        )
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "upload")
            .addFormDataPart("format", "json")
            .addFormDataPart("formatversion", "2")
            .addFormDataPart("stash", "1")
            .addFormDataPart("ignorewarnings", "1")
            .addFormDataPart("filename", fileName)
            .addFormDataPart("filesize", totalSize.toString())
            .addFormDataPart("offset", offset.toString())
            .addFormDataPart("token", token)
            .addFormDataPart("assert", "user")
        fileKey?.takeIf { it.isNotBlank() }?.let { builder.addFormDataPart("filekey", it) }
        builder.addFormDataPart(
            "chunk",
            URLEncoder.encode(fileName, "UTF-8"),
            requestBody,
        )
        parseStashResponse(execute(builder.build()), offset, length.toLong())
    }

    /**
     * Publishes the completed stash as a normal Commons file page.
     *
     * @param fileName unique file name chosen with [uniqueFileName]
     * @param fileKey stash key returned by the last chunk
     */
    suspend fun publishFromStash(
        fileName: String,
        wikitext: String,
        comment: String,
        fileKey: String,
        token: String,
    ): UploadedFile = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("action", "upload")
            .add("format", "json")
            .add("formatversion", "2")
            .add("ignorewarnings", "1")
            .add("token", token)
            .add("filename", fileName)
            .add("comment", comment)
            .add("text", wikitext)
            .add("filekey", fileKey)
            .add("assert", "user")
            .build()
        parsePublishResponse(execute(body), fileName)
    }

    private fun fileExists(fileName: String): Boolean {
        val body = FormBody.Builder()
            .add("action", "query")
            .add("titles", "File:$fileName")
            .add("format", "json")
            .add("formatversion", "2")
            .build()
        val root = execute(body)
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray ?: return false
        val page = pages.firstOrNull()?.jsonObject ?: return false
        return page["missing"]?.jsonPrimitive?.booleanOrNull != true
    }

    private fun fetchToken(type: String): String {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "query")
            .addFormDataPart("meta", "tokens")
            .addFormDataPart("type", type)
            .addFormDataPart("format", "json")
            .addFormDataPart("formatversion", "2")
        if (type == "csrf") {
            builder.addFormDataPart("assert", "user")
        }
        val root = execute(builder.build())
        val token = root["query"]?.jsonObject
            ?.get("tokens")?.jsonObject
            ?.string(if (type == "login") "logintoken" else "csrftoken")
        return token ?: throw CommonsApiException(
            root.errorCode() ?: "token-failed",
            root.errorText() ?: "Could not get a Commons token",
        )
    }

    private fun execute(body: RequestBody): JsonObject {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", Network.USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) {
                throw CommonsApiException(
                    "http-${response.code}",
                    "Empty response from Commons (HTTP ${response.code})",
                    httpStatus = response.code,
                )
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw CommonsApiException(
                    "http-${response.code}",
                    "Unexpected response from Commons (HTTP ${response.code})",
                    httpStatus = response.code,
                )
            }
            root["error"]?.let { error ->
                val obj = error.jsonObject
                throw CommonsApiException(
                    obj.string("code").orEmpty(),
                    obj.string("info") ?: "Commons API error",
                    httpStatus = response.code,
                )
            }
            if (!response.isSuccessful) {
                throw CommonsApiException(
                    "http-${response.code}",
                    "Commons API failed (HTTP ${response.code})",
                    httpStatus = response.code,
                )
            }
            return root
        }
    }

    private class ChunkRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val totalSize: Long,
        private val onProgress: (uploaded: Long, total: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = "image/jpeg".toMediaType()

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var sent = 0L
                while (sent < length) {
                    val toRead = minOf(buffer.size.toLong(), length - sent).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) {
                        throw IOException("The photo file ended before the chunk was read")
                    }
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress(minOf(offset + sent, totalSize), totalSize)
                }
            }
        }
    }

    private companion object {
        const val API_URL = "https://commons.wikimedia.org/w/api.php"
    }
}

/**
 * Parses the response of ``action=upload&stash=1``.
 *
 * Pure function, unit tested without a network.
 */
internal fun parseStashResponse(
    root: JsonObject,
    previousOffset: Long,
    sentLength: Long,
): StashChunkResult {
    val upload = root["upload"]?.jsonObject
        ?: throw CommonsApiException(
            root.errorCode() ?: "stashfailed",
            root.errorText() ?: "Commons did not accept the upload chunk",
        )
    val fileKey = upload.string("filekey")
    return when (val result = upload.string("result")) {
        "Continue" -> {
            val offset = upload.string("offset")?.toLongOrNull()
                ?: throw CommonsApiException(
                    "stashfailed",
                    "Commons did not return the stash offset",
                )
            StashChunkResult(
                fileKey = fileKey?.takeIf { it.isNotBlank() }
                    ?: throw CommonsApiException("stashfailed", "Commons did not return a file key"),
                offset = offset,
                completed = false,
            )
        }
        "Success" -> StashChunkResult(
            fileKey = fileKey?.takeIf { it.isNotBlank() }
                ?: throw CommonsApiException("stashfailed", "Commons did not return a file key"),
            offset = previousOffset + sentLength,
            completed = true,
        )
        "Failure" -> throw CommonsApiException(
            "stashfailed",
            upload.string("info") ?: "Commons could not stash the upload",
        )
        else -> throw CommonsApiException(
            upload.string("code") ?: result?.lowercase() ?: "stashfailed",
            upload.string("info") ?: "Commons rejected the upload chunk",
        )
    }
}

/**
 * Parses the response of the final ``action=upload`` that publishes the stash.
 *
 * Pure function, unit tested without a network.
 */
internal fun parsePublishResponse(root: JsonObject, fallbackName: String): UploadedFile {
    val upload = root["upload"]?.jsonObject
        ?: throw CommonsApiException(
            root.errorCode() ?: "upload-failed",
            root.errorText() ?: "Upload failed",
        )
    return when (val result = upload.string("result")) {
        "Success" -> {
            val publishedName = p18FileTitle(upload.string("filename") ?: fallbackName)
            UploadedFile(
                fileName = publishedName,
                pageUrl = descriptionUrl(upload, publishedName),
            )
        }
        "Warning" -> {
            val warnings = upload["warnings"]?.jsonObject
                ?.entries
                ?.joinToString("; ") { (key, value) -> "$key: ${warningText(value)}" }
                .orEmpty()
            throw CommonsApiException(
                "warning",
                warnings.ifBlank { "The upload was blocked by a Commons warning" },
            )
        }
        "Failure" -> throw CommonsApiException(
            "upload-failed",
            upload.string("info") ?: "The upload failed",
        )
        else -> throw CommonsApiException(
            upload.string("code") ?: result?.lowercase() ?: "upload-failed",
            upload.string("info") ?: upload.string("reason") ?: "Upload failed",
        )
    }
}

/**
 * MediaWiki canonicalises file titles with underscores, while Wikidata P18 expects the
 * title with spaces. The original Commons app makes the same conversion before editing.
 */
internal fun p18FileTitle(fileName: String): String = fileName.replace('_', ' ').trim()

private fun warningText(value: kotlinx.serialization.json.JsonElement): String = when (value) {
    is JsonObject -> value.string("html") ?: value.string("text") ?: value.toString()
    else -> value.toString()
}

private fun descriptionUrl(upload: JsonObject, fileName: String): String {
    val imageInfo = upload["imageinfo"]
    val info = when (imageInfo) {
        is JsonArray -> imageInfo.firstOrNull() as? JsonObject
        is JsonObject -> imageInfo
        else -> null
    }
    info?.string("descriptionurl")?.let { return it }
    val title = fileName.replace(' ', '_')
    return "https://commons.wikimedia.org/wiki/File:" +
        URLEncoder.encode(title, "UTF-8").replace("+", "%20")
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.errorCode(): String? =
    (this["error"] as? JsonObject)?.string("code")

private fun JsonObject.errorText(): String? =
    (this["error"] as? JsonObject)?.string("info")
