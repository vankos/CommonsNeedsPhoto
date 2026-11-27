package com.example.commonsneedsphoto.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException

class WikidataApiException(
    val code: String,
    message: String,
) : IOException(message)

/**
 * Adds the uploaded Commons file to the Wikidata item as the P18 (image) claim.
 *
 * This is the step that makes the point disappear from the "no photo" map for
 * good: without P18 the SPARQL query will return the item again after a refresh.
 * The edit is best effort: it needs an authenticated Wikimedia session with
 * Wikidata edit rights, and a failure must not undo the Commons upload.
 *
 * Authentication on www.wikidata.org is not the same as on Commons: the session
 * cookies from a normal login are often not usable there. A normal Commons login
 * therefore asks Commons for a short-lived `centralauthtoken` and repeats every
 * authenticated Wikidata request in a one-request CentralAuth session.
 *
 * A bot-password session cannot mint a centralauthtoken (MediaWiki uses a
 * different session provider for it). Bot passwords are shared across Wikimedia
 * wikis, so [loginWithBotPassword] signs the app in to Wikidata with the same
 * bot password right after the Commons login and keeps that session in the jar.
 */
class WikidataApi(
    cookieJar: okhttp3.CookieJar,
    baseClient: OkHttpClient = Network.client,
    private val wikidataApiUrl: String = WIKIDATA_API_URL,
    private val commonsApiUrl: String = COMMONS_API_URL,
) {
    private val client = baseClient.newBuilder()
        .cookieJar(cookieJar)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return true when P18 is set (now or already), false is never returned:
     * failures throw so the caller can show a warning.
     */
    suspend fun addImageClaim(qid: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (hasImageClaim(qid)) return@withContext true
            val auth = authenticatedCsrfToken()
            // A centralauthtoken is single-use and short-lived, so the edit
            // needs its own token, fresh from Commons.
            val centralAuthToken = if (auth.usesCentralAuth) fetchCentralAuthToken() else null
            val builder = FormBody.Builder()
                .add("action", "wbeditentity")
                .add("format", "json")
                .add("formatversion", "2")
                .add("id", qid)
                .add("data", buildP18EditData(fileName).toString())
                .add("summary", EDIT_SUMMARY)
                .add("token", auth.csrfToken)
                .add("assert", "user")
            if (centralAuthToken != null) {
                builder.add("centralauthtoken", centralAuthToken)
            }
            val root = request("Wikidata", wikidataApiUrl, builder.build())
            val success = root["success"]?.jsonPrimitive?.intOrNull == 1
            if (!success) {
                throw WikidataApiException(
                    "p18-failed",
                    root.errorText() ?: "Wikidata did not accept the P18 claim",
                )
            }
            true
        }

    /**
     * Logs in on Wikidata with the same bot password that was used for Commons.
     *
     * Needed because `action=centralauthtoken` rejects bot-password sessions
     * (`badsession`). Wikimedia shares bot passwords across SUL wikis, so the
     * credentials are valid here too and the grant set is the same.
     */
    suspend fun loginWithBotPassword(loginName: String, password: String) =
        withContext(Dispatchers.IO) {
            val loginToken = fetchLoginToken()
            val body = FormBody.Builder()
                .add("action", "login")
                .add("format", "json")
                .add("formatversion", "2")
                .add("lgname", loginName.trim())
                .add("lgpassword", password)
                .add("lgtoken", loginToken)
                .build()
            val login = request("Wikidata", wikidataApiUrl, body)["login"]?.jsonObject
            if (login.string("result") != "Success") {
                throw WikidataApiException(
                    "login-failed",
                    login.string("reason")
                        ?: login.string("message")
                        ?: "Wikidata login failed",
                )
            }
        }

    private fun fetchLoginToken(): String {
        val body = FormBody.Builder()
            .add("action", "query")
            .add("meta", "tokens")
            .add("type", "login")
            .add("format", "json")
            .add("formatversion", "2")
            .build()
        val root = request("Wikidata", wikidataApiUrl, body)
        return tokenOrThrow(root, "logintoken", "Could not get a Wikidata login token")
    }

    private fun hasImageClaim(qid: String): Boolean {
        val body = FormBody.Builder()
            .add("action", "wbgetclaims")
            .add("entity", qid)
            .add("property", "P18")
            .add("format", "json")
            .add("formatversion", "2")
            .build()
        val root = request("Wikidata", wikidataApiUrl, body)
        val claims = root["claims"]?.jsonObject?.get("P18")?.jsonArray
        return !claims.isNullOrEmpty()
    }

    /**
     * A CSRF token for the Wikidata session.
     *
     * First tries the cookies that the jar already has: if the browser-style
     * CentralAuth cookies survived the Commons login, Wikidata accepts them.
     * When Wikidata answers as an anonymous user, the request is repeated with
     * a fresh `centralauthtoken` from Commons.
     */
    private fun authenticatedCsrfToken(): WikidataAuth {
        try {
            return WikidataAuth(fetchCsrfToken(centralAuthToken = null), usesCentralAuth = false)
        } catch (exception: WikidataApiException) {
            if (exception.code !in SESSION_ERROR_CODES) throw exception
        }
        val centralAuthToken = fetchCentralAuthToken()
        return WikidataAuth(
            csrfToken = fetchCsrfToken(centralAuthToken),
            usesCentralAuth = true,
        )
    }

    private fun fetchCsrfToken(centralAuthToken: String?): String {
        val builder = FormBody.Builder()
            .add("action", "query")
            .add("meta", "tokens")
            .add("type", "csrf")
            .add("format", "json")
            .add("formatversion", "2")
            .add("assert", "user")
        if (centralAuthToken != null) {
            builder.add("centralauthtoken", centralAuthToken)
        }
        val root = request("Wikidata", wikidataApiUrl, builder.build())
        return tokenOrThrow(root, "csrftoken", "Could not get a Wikidata CSRF token")
    }

    private fun fetchCentralAuthToken(): String {
        val body = FormBody.Builder()
            .add("action", "centralauthtoken")
            .add("format", "json")
            .add("formatversion", "2")
            .add("assert", "user")
            .build()
        val root = request("Commons", commonsApiUrl, body)
        return parseCentralAuthToken(root)
            ?: throw WikidataApiException(
                root.errorCode() ?: "centralauthtoken-failed",
                root.errorText() ?: "Could not get a CentralAuth token from Commons",
            )
    }

    private fun tokenOrThrow(root: JsonObject, name: String, fallback: String): String {
        val token = root["query"]?.jsonObject
            ?.get("tokens")?.jsonObject
            ?.string(name)
        return token ?: throw WikidataApiException(
            root.errorCode() ?: "token-failed",
            root.errorText() ?: fallback,
        )
    }

    private fun request(apiName: String, url: String, body: RequestBody): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Network.USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) {
                throw IOException("Empty response from $apiName (HTTP ${response.code})")
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw IOException("Unexpected response from $apiName (HTTP ${response.code})")
            }
            root["error"]?.let { error ->
                val obj = error.jsonObject
                throw WikidataApiException(
                    obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    obj["info"]?.jsonPrimitive?.contentOrNull ?: "$apiName API error",
                )
            }
            if (!response.isSuccessful) {
                throw IOException("$apiName API failed (HTTP ${response.code})")
            }
            return root
        }
    }

    private fun JsonObject.errorCode(): String? =
        (this["error"] as? JsonObject)
            ?.get("code")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.errorText(): String? =
        (this["error"] as? JsonObject)
            ?.get("info")?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private data class WikidataAuth(
        val csrfToken: String,
        /** true when the token was obtained in a one-request CentralAuth session. */
        val usesCentralAuth: Boolean,
    )

    private companion object {
        const val WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php"
        const val COMMONS_API_URL = "https://commons.wikimedia.org/w/api.php"
        const val EDIT_SUMMARY = "Adding image from Wikimedia Commons (Commons needs photo MVP)"

        /** Codes that mean "the jar has no usable Wikidata session". */
        val SESSION_ERROR_CODES = setOf(
            "assertuserfailed",
            "mustbeloggedin",
            "notloggedin",
        )
    }
}

/**
 * Reads the token from an `action=centralauthtoken` response.
 *
 * The API answers with a nested object (`{"centralauthtoken": {"centralauthtoken": "…"}}`);
 * the plain shape is accepted as well because the formatversion history of the
 * module is not consistent across releases.
 *
 * Pure function, unit tested without Android or network.
 */
internal fun parseCentralAuthToken(root: JsonObject): String? =
    when (val value = root["centralauthtoken"]) {
        is JsonObject -> value["centralauthtoken"]?.jsonPrimitive?.contentOrNull
        null -> null
        else -> value.jsonPrimitive.contentOrNull
    }?.takeIf { it.isNotBlank() }

/**
 * The `data` payload for `action=wbeditentity` that adds the image as a new
 * P18 statement.
 *
 * `action=wbsetclaim` cannot be used here: it always requires the GUID of the
 * statement ("GUID must be set when setting a claim") and a new statement has
 * none yet. `wbeditentity` merges the given claims into the item and lets
 * Wikibase assign the GUID, which the API documents as the way to create a
 * claim (`data={"claims":[{…}]}`).
 *
 * Kept as a pure function so it can be tested without Android or network.
 */
internal fun buildP18EditData(fileName: String): JsonObject = buildJsonObject {
    put("claims", buildJsonArray { add(buildP18Claim(fileName)) })
}

/**
 * The JSON representation of a new P18 (image) statement. It carries no `id`:
 * Wikibase assigns the GUID when the statement is added through
 * [buildP18EditData].
 */
internal fun buildP18Claim(fileName: String): JsonObject = buildJsonObject {
    put(
        "mainsnak",
        buildJsonObject {
            put("snaktype", "value")
            put("property", "P18")
            put(
                "datavalue",
                buildJsonObject {
                    put("value", fileName)
                    put("type", "string")
                },
            )
        },
    )
    put("type", "statement")
    put("rank", "normal")
}
