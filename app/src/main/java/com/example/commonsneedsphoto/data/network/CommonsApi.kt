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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.random.Random

class CommonsApiException(
    val code: String,
    message: String,
) : IOException(message)

data class UploadedFile(
    val fileName: String,
    val pageUrl: String,
)

/**
 * Minimal Wikimedia Commons client for the MVP upload flow:
 * bot-password login -> CSRF token -> multipart action=upload.
 *
 * Mirrors the endpoint/parameters used by the original Commons Android app.
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

    suspend fun upload(
        file: File,
        fileName: String,
        wikitext: String,
        comment: String,
        onProgress: (uploaded: Long, total: Long) -> Unit,
    ): UploadedFile = withContext(Dispatchers.IO) {
        val token = fetchToken("csrf")
        val finalName = findUniqueFileName(fileName)
        val mediaType = "image/jpeg".toMediaType()
        val requestFile = ProgressRequestBody(file, mediaType, onProgress)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", "upload")
            .addFormDataPart("format", "json")
            .addFormDataPart("formatversion", "2")
            .addFormDataPart("filename", finalName)
            .addFormDataPart("comment", comment)
            .addFormDataPart("text", wikitext)
            .addFormDataPart("token", token)
            .addFormDataPart("assert", "user")
            .addFormDataPart("ignorewarnings", "1")
            .addFormDataPart("file", finalName, requestFile)
            .build()
        val root = execute(body)
        val upload = root["upload"]?.jsonObject
            ?: throw CommonsApiException(
                root.errorCode() ?: "upload-failed",
                root.errorText() ?: "Upload failed",
            )
        when (upload.string("result")) {
            "Success" -> {
                val uploadedName = upload.string("filename") ?: finalName
                val pageUrl = descriptionUrl(upload, uploadedName)
                UploadedFile(uploadedName, pageUrl)
            }
            "Warning" -> {
                val warnings = upload["warnings"]?.jsonObject
                    ?.entries
                    ?.joinToString("; ") { (key, value) -> "$key: ${value.jsonPrimitive.contentOrNull.orEmpty()}" }
                    .orEmpty()
                throw CommonsApiException(
                    "warning",
                    warnings.ifBlank { "The upload was blocked by a Commons warning" },
                )
            }
            else -> throw CommonsApiException(
                upload.string("result").orEmpty().ifBlank { "upload-failed" },
                upload.string("reason") ?: "Upload failed",
            )
        }
    }

    /**
     * The original app appends a random suffix to a name that already exists on
     * Commons, so a new upload never overwrites somebody else's file.
     */
    private fun findUniqueFileName(fileName: String): String {
        var candidate = fileName
        repeat(5) {
            if (!fileExists(candidate)) return candidate
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
                throw IOException("Empty response from Commons (HTTP ${response.code})")
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw IOException("Unexpected response from Commons (HTTP ${response.code})")
            }
            root["error"]?.let { error ->
                val obj = error.jsonObject
                throw CommonsApiException(
                    obj.string("code").orEmpty(),
                    obj.string("info") ?: "Commons API error",
                )
            }
            if (!response.isSuccessful) {
                throw IOException("Commons API failed (HTTP ${response.code})")
            }
            return root
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.errorCode(): String? =
        (this["error"] as? JsonObject)?.string("code")

    private fun JsonObject.errorText(): String? =
        (this["error"] as? JsonObject)?.string("info")

    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (uploaded: Long, total: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var uploaded = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    onProgress(uploaded, total)
                }
            }
        }
    }

    private companion object {
        const val API_URL = "https://commons.wikimedia.org/w/api.php"
    }
}
