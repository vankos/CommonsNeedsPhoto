package com.example.commonsneedsphoto.data.network

import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Locale

/**
 * Runs the same "Wikidata items without a photo" SPARQL query that the
 * NoPhotoWikidata / StuffAround apps use, and parses the bindings for the map.
 */
class WikidataNearbyRepository(
    private val httpClient: okhttp3.OkHttpClient = Network.client,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun findPlacesWithoutPhoto(
        center: LatLon,
        radiusKm: Double,
        limit: Int = 500,
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val query = buildQuery(center, radiusKm, limit)
        withFallbackLabels(parse(sparqlRequest(query)))
    }

    private fun sparqlRequest(query: String): String {
        val url = "https://query.wikidata.org/sparql".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", Network.USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Wikidata query failed (HTTP ${response.code})")
            }
            return response.body?.string().orEmpty()
        }
    }

    /**
     * The WDQS label service substitutes the entity ID when the item has no
     * label in any of the requested languages. For those items only, fetch all
     * labels from the Wikidata API and replace the ID with any available label,
     * so a place whose only labels are Spanish and Catalan still has a readable
     * title.
     *
     * The lookup is best effort: a failure leaves the QID in place and does not
     * fail the whole search.
     */
    private fun withFallbackLabels(places: List<NearbyPlace>): List<NearbyPlace> {
        val qids = places.asSequence()
            .filter { it.label == it.qid }
            .map { it.qid }
            .distinct()
            .toList()
        if (qids.isEmpty()) return places
        val labels = try {
            fetchLabels(qids)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return places
        }
        return applyFallbackLabels(places, labels)
    }

    /**
     * Reads labels in every language for the given items. `wbgetentities`
     * accepts at most 50 IDs per request; even with `formatversion=2` the
     * `entities` value is an object keyed by entity ID.
     */
    private fun fetchLabels(qids: List<String>): Map<String, String> {
        val labels = LinkedHashMap<String, String>()
        for (chunk in qids.chunked(MAX_IDS_PER_LABEL_REQUEST)) {
            val url = WIKIDATA_API_URL.toHttpUrl().newBuilder()
                .addQueryParameter("action", "wbgetentities")
                .addQueryParameter("ids", chunk.joinToString("|"))
                .addQueryParameter("props", "labels")
                .addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", Network.USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Wikidata labels request failed (HTTP ${response.code})")
                }
                labels += parseLabels(response.body?.string().orEmpty())
            }
        }
        return labels
    }

    /**
     * Parses a `wbgetentities&props=labels` response and picks one label per
     * item. The preferred languages follow the same order as the SPARQL fallback
     * chain: the device language, English, `mul`, then any available label
     * (deterministically, by language code).
     */
    internal fun parseLabels(
        body: String,
        preferredLanguages: List<String> = preferredLabelLanguages(),
    ): Map<String, String> {
        val entities = json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
            ?: return emptyMap()
        val labels = LinkedHashMap<String, String>()
        for ((qid, element) in entities) {
            val itemLabels = element.jsonObject["labels"]?.jsonObject ?: continue
            choosePreferredLabel(itemLabels, preferredLanguages)?.let { labels[qid] = it }
        }
        return labels
    }

    private fun preferredLabelLanguages(): List<String> {
        val language = Locale.getDefault().language.ifBlank { "en" }
        return listOf(language, "en", "mul").distinct()
    }

    internal fun buildQuery(center: LatLon, radiusKm: Double, limit: Int): String {
        val labelLanguages = preferredLabelLanguages().joinToString(",")
        val radius = String.format(Locale.ROOT, "%.2f", radiusKm)
        return """
            SELECT ?q ?qLabel ?desc ?location
                   (SAMPLE(?category) AS ?commonsCategory)
                   (GROUP_CONCAT(DISTINCT ?instanceOfLabel; separator=", ") AS ?instanceOfLabels)
            WHERE {
              SERVICE wikibase:around {
                ?q wdt:P625 ?location .
                bd:serviceParam wikibase:center "Point(${center.longitude} ${center.latitude})"^^geo:wktLiteral .
                bd:serviceParam wikibase:radius "$radius" .
              }
              FILTER NOT EXISTS { ?q wdt:P18 ?image }
              FILTER NOT EXISTS { ?q wdt:P576 ?dissolvedDate }
              FILTER NOT EXISTS { ?q wdt:P5816 wd:Q56556915 }
              OPTIONAL { ?q wdt:P373 ?category }
              OPTIONAL {
                ?q wdt:P31 ?instanceOf .
                ?instanceOf rdfs:label ?instanceOfLabel .
                FILTER(LANG(?instanceOfLabel) = "en")
              }
              SERVICE wikibase:label {
                bd:serviceParam wikibase:language "$labelLanguages" .
                ?q rdfs:label ?qLabel .
                ?q schema:description ?desc .
              }
            }
            GROUP BY ?q ?qLabel ?desc ?location
            LIMIT $limit
        """.trimIndent()
    }

    internal fun parse(body: String): List<NearbyPlace> {
        val root = json.parseToJsonElement(body).jsonObject
        val bindings = root["results"]?.jsonObject?.get("bindings")?.jsonArray ?: return emptyList()
        val places = ArrayList<NearbyPlace>(bindings.size)
        for (element in bindings) {
            val binding = element.jsonObject
            val qidUri = binding.getValue("q").jsonObject.value("value") ?: continue
            val qid = qidUri.substringAfterLast('/')
            val location = binding["location"]?.jsonObject?.value("value") ?: continue
            val point = parsePoint(location) ?: continue
            val label = binding["qLabel"]?.jsonObject?.value("value")
                ?.takeIf { it.isNotBlank() }
                ?: qid
            places += NearbyPlace(
                qid = qid,
                label = label,
                description = binding["desc"]?.jsonObject?.value("value")?.takeIf { it.isNotBlank() },
                latitude = point.first,
                longitude = point.second,
                instanceOf = binding["instanceOfLabels"]?.jsonObject?.value("value")
                    ?.takeIf { it.isNotBlank() },
                commonsCategory = binding["commonsCategory"]?.jsonObject?.value("value")
                    ?.takeIf { it.isNotBlank() },
            )
        }
        return places
    }

    private fun JsonObject.value(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    /** Parses a WKT literal {@code Point(longitude latitude)}. */
    private fun parsePoint(wkt: String): Pair<Double, Double>? {
        val match = POINT_REGEX.find(wkt) ?: return null
        val longitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val latitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return latitude to longitude
    }

    private companion object {
        val POINT_REGEX = Regex("""Point\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)""")
        const val WIKIDATA_API_URL = "https://www.wikidata.org/w/api.php"

        /** The `wbgetentities` action accepts at most 50 IDs per request. */
        const val MAX_IDS_PER_LABEL_REQUEST = 50
    }
}

/**
 * Picks the first non-blank label in [preferredLanguages], then falls back to
 * any label, ordered by language code so the choice is stable.
 *
 * The default chain of the nearby query already covers the device language and
 * English in SPARQL; this function is used for the API response of items whose
 * QID was returned instead of a label.
 */
internal fun choosePreferredLabel(
    labels: JsonObject,
    preferredLanguages: List<String>,
): String? {
    for (language in preferredLanguages) {
        val label = labels[language]?.jsonObject?.get("value")
            ?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (label != null) return label
    }
    return labels.entries
        .sortedBy { it.key }
        .firstNotNullOfOrNull { (_, value) ->
            value.jsonObject["value"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
}

/**
 * Replaces the QID placeholder of a place with a label fetched in another
 * language. Places that already have a real label are left untouched.
 */
internal fun applyFallbackLabels(
    places: List<NearbyPlace>,
    labels: Map<String, String>,
): List<NearbyPlace> {
    if (labels.isEmpty()) return places
    return places.map { place ->
        if (place.label != place.qid) {
            place
        } else {
            labels[place.qid]?.takeIf { it.isNotBlank() }?.let { place.copy(label = it) } ?: place
        }
    }
}
