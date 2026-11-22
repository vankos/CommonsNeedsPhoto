package com.example.commonsneedsphoto.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 */
class WikidataApi(
    session: CommonsSession,
    baseClient: OkHttpClient = Network.client,
) {
    private val client = baseClient.newBuilder()
        .cookieJar(session)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return true when P18 is set (now or already), false is never returned:
     * failures throw so the caller can show a warning.
     */
    suspend fun addImageClaim(qid: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (hasImageClaim(qid)) return@withContext true
            val token = fetchCsrfToken()
            val body = FormBody.Builder()
                .add("action", "wbsetclaim")
                .add("format", "json")
                .add("formatversion", "2")
                .add("claim", buildP18Claim(fileName).toString())
                .add("summary", EDIT_SUMMARY)
                .add("token", token)
                .add("assert", "user")
                .build()
            val root = request(body)
            val success = root["success"]?.jsonPrimitive?.intOrNull == 1
            if (!success) {
                throw WikidataApiException(
                    "p18-failed",
                    root.errorText() ?: "Wikidata did not accept the P18 claim",
                )
            }
            true
        }

    private fun hasImageClaim(qid: String): Boolean {
        val body = FormBody.Builder()
            .add("action", "wbgetclaims")
            .add("entity", qid)
            .add("property", "P18")
            .add("format", "json")
            .add("formatversion", "2")
            .build()
        val root = request(body)
        val claims = root["claims"]?.jsonObject?.get("P18")?.jsonArray
        return !claims.isNullOrEmpty()
    }

    private fun fetchCsrfToken(): String {
        val body = FormBody.Builder()
            .add("action", "query")
            .add("meta", "tokens")
            .add("type", "csrf")
            .add("format", "json")
            .add("formatversion", "2")
            .add("assert", "user")
            .build()
        val root = request(body)
        return root["query"]?.jsonObject
            ?.get("tokens")?.jsonObject
            ?.get("csrftoken")?.jsonPrimitive?.contentOrNull
            ?: throw WikidataApiException(
                root.errorCode() ?: "token-failed",
                root.errorText() ?: "Could not get a Wikidata CSRF token",
            )
    }

    private fun request(body: RequestBody): JsonObject {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", Network.USER_AGENT)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) {
                throw IOException("Empty response from Wikidata (HTTP ${response.code})")
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw IOException("Unexpected response from Wikidata (HTTP ${response.code})")
            }
            root["error"]?.let { error ->
                val obj = error.jsonObject
                throw WikidataApiException(
                    obj["code"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    obj["info"]?.jsonPrimitive?.contentOrNull ?: "Wikidata API error",
                )
            }
            if (!response.isSuccessful) {
                throw IOException("Wikidata API failed (HTTP ${response.code})")
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

    private companion object {
        const val API_URL = "https://www.wikidata.org/w/api.php"
        const val EDIT_SUMMARY = "Adding image from Wikimedia Commons (Commons needs photo MVP)"
    }
}

/**
 * The JSON object accepted by `action=wbsetclaim` for a Wikidata image claim.
 * Kept as a pure function so it can be tested without Android or network.
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
