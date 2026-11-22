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

enum class TwoFactorMethod { TOTP, EMAIL }

sealed interface LoginResult {
    data class Success(val userName: String) : LoginResult
    data class VerificationRequired(val method: TwoFactorMethod) : LoginResult
}

private data class PendingVerification(
    val loginName: String,
    val password: String,
    val loginToken: String,
    val method: TwoFactorMethod,
)

/**
 * Minimal Wikimedia Commons client for the MVP upload flow:
 * account (or bot-password) login -> optional two-factor step -> CSRF token ->
 * multipart action=upload.
 *
 * The account login uses the same action=clientlogin flow as the original
 * Commons Android app, including the second step for TOTP/email verification.
 */
class CommonsApi(
    private val session: CommonsSession,
    baseClient: OkHttpClient = Network.client,
) {
    private val client = baseClient.newBuilder()
        .cookieJar(session)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var pendingVerification: PendingVerification? = null

    suspend fun login(loginName: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            pendingVerification = null
            if (loginName.trim().contains('@')) {
                LoginResult.Success(loginWithBotPassword(loginName, password))
            } else {
                val name = loginName.trim()
                val token = fetchToken("login")
                parseAccountLogin(
                    root = postClientLogin(name, password, token),
                    loginName = name,
                    password = password,
                    loginToken = token,
                )
            }
        }

    /**
     * The second `clientlogin` request. It reuses the logintoken and keeps the
     * credentials in memory only for the duration of the verification step.
     */
    suspend fun completeTwoFactor(code: String): LoginResult =
        withContext(Dispatchers.IO) {
            val pending = pendingVerification ?: throw CommonsApiException(
                "login-failed",
                "The login attempt expired. Start again.",
            )
            parseAccountLogin(
                root = postClientLogin(
                    loginName = pending.loginName,
                    password = pending.password,
                    loginToken = pending.loginToken,
                    code = code.trim(),
                    method = pending.method,
                ),
                loginName = pending.loginName,
                password = pending.password,
                loginToken = pending.loginToken,
            )
        }

    fun resetLogin() {
        pendingVerification = null
    }

    private fun postClientLogin(
        loginName: String,
        password: String,
        loginToken: String,
        code: String? = null,
        method: TwoFactorMethod? = null,
    ): JsonObject {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        for ((name, value) in clientLoginFields(loginName, password, loginToken, code, method)) {
            builder.addFormDataPart(name, value)
        }
        return execute(builder.build())
    }

    private fun parseAccountLogin(
        root: JsonObject,
        loginName: String,
        password: String,
        loginToken: String,
    ): LoginResult {
        val clientlogin = root["clientlogin"]?.jsonObject
            ?: throw CommonsApiException("login-failed", root.errorText() ?: "Login failed")
        return when (clientlogin.string("status")) {
            "PASS" -> {
                val name = clientlogin.string("username") ?: loginName
                session.userName = name
                pendingVerification = null
                LoginResult.Success(name)
            }
            "UI" -> {
                val required = parseVerificationRequest(clientlogin)
                pendingVerification = PendingVerification(
                    loginName = loginName,
                    password = password,
                    loginToken = loginToken,
                    method = required.method,
                )
                required
            }
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
        pendingVerification = null
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

/**
 * Fields of the first and second `action=clientlogin` requests. Kept pure so
 * the two-factor branch can be tested without a network or an account.
 */
internal fun clientLoginFields(
    loginName: String,
    password: String,
    loginToken: String,
    code: String? = null,
    method: TwoFactorMethod? = null,
): List<Pair<String, String>> = buildList {
    add("action" to "clientlogin")
    add("format" to "json")
    add("formatversion" to "2")
    add("username" to loginName)
    add("password" to password)
    add("logintoken" to loginToken)
    add("uselang" to "en")
    if (code == null) {
        add("loginreturnurl" to "https://commons.wikimedia.org/")
    } else {
        when (method) {
            TwoFactorMethod.TOTP -> add("OATHToken" to code)
            TwoFactorMethod.EMAIL -> add("token" to code)
            null -> Unit
        }
        add("logincontinue" to "1")
    }
}

internal fun parseVerificationRequest(clientlogin: JsonObject): LoginResult.VerificationRequired {
    val requests = clientlogin["requests"]?.jsonArray ?: JsonArray(emptyList())
    val ids = requests.mapNotNull { request ->
        request.jsonObject["id"]?.jsonPrimitive?.contentOrNull
    }
    val method = when {
        ids.any { it.endsWith("TOTPAuthenticationRequest") } -> TwoFactorMethod.TOTP
        ids.any { it.endsWith("EmailAuthAuthenticationRequest") } -> TwoFactorMethod.EMAIL
        else -> throw CommonsApiException(
            "login-verification",
            "This account asks for a CAPTCHA or another verification step. " +
                "Please use a bot password (name@bot).",
        )
    }
    return LoginResult.VerificationRequired(method)
}
