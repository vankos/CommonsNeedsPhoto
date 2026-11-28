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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs the same "Wikidata items without a photo" SPARQL query that the
 * NoPhotoWikidata / StuffAround apps use, and parses the bindings for the map.
 */
class WikidataNearbyRepository(
    private val httpClient: okhttp3.OkHttpClient = Network.client,
    private val sparqlUrl: String = "https://query.wikidata.org/sparql",
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `type QID -> P279 ancestor labels`, shared by all searches in this
     * process. Wikidata classes change slowly and the same types repeat across
     * nearby searches, so only types that were never seen before are queried.
     */
    private val ancestorCache = ConcurrentHashMap<String, List<String>>()

    /**
     * Ancestors are an enhancement, so their request is capped instead of using
     * the shared client's 60 s read timeout: a slow WDQS must not turn a nearby
     * search into a minute-long wait. A timeout falls back to direct types.
     */
    private val ancestorHttpClient: okhttp3.OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(ANCESTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun findPlacesWithoutPhoto(
        center: LatLon,
        radiusKm: Double,
        limit: Int = 500,
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        val query = buildQuery(center, radiusKm, limit)
        val places = parse(sparqlRequest(query))
        withFallbackLabels(withAncestorTypes(places))
    }

    private fun sparqlRequest(
        query: String,
        client: okhttp3.OkHttpClient = httpClient,
    ): String {
        val url = sparqlUrl.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/sparql-results+json")
            .header("User-Agent", Network.USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
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
     * Adds the direct `P279` parents and grandparents of every distinct `P31`
     * type in [places], in one extra request. Two levels cover the usual
     * exclusion hierarchy (`literary award` -> `award`); the full `P279+`
     * closure returns tens of thousands of rows for a dense city and does not
     * finish inside the timeout, which would silently disable the filter.
     *
     * The path is anchored with `VALUES` on the type QIDs instead of being
     * joined to the geo query, where WDQS walks the class graph per item.
     *
     * Best effort, like [withFallbackLabels]: when the request fails the places
     * keep their direct types and are filtered by those only.
     */
    private fun withAncestorTypes(places: List<NearbyPlace>): List<NearbyPlace> {
        val types = places.asSequence()
            .flatMap { it.typeQids.asSequence() }
            .distinct()
            .toList()
        if (types.isEmpty()) return places
        val unknown = types.filterNot(ancestorCache::containsKey)
        if (unknown.isNotEmpty()) {
            try {
                ancestorCache.putAll(fetchAncestors(unknown))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the direct types; the filter still works without ancestors.
            }
        }
        return applyAncestors(places, ancestorCache)
    }

    private fun fetchAncestors(typeQids: List<String>): Map<String, List<String>> {
        val ancestors = LinkedHashMap<String, List<String>>()
        for (chunk in typeQids.chunked(MAX_TYPES_PER_ANCESTOR_REQUEST)) {
            ancestors += parseAncestors(
                sparqlRequest(buildAncestorQuery(chunk), ancestorHttpClient),
                chunk,
            )
        }
        return ancestors
    }

    internal fun buildAncestorQuery(typeQids: List<String>): String {
        val types = typeQids.joinToString(" ") { "wd:$it" }
        return """
            SELECT DISTINCT ?type ?ancestorLabel WHERE {
              VALUES ?type { $types }
              ?type wdt:P279/wdt:P279? ?ancestor .
              ?ancestor rdfs:label ?ancestorLabel .
              FILTER(LANG(?ancestorLabel) = "en")
            }
        """.trimIndent()
    }

    /**
     * Parses a [buildAncestorQuery] response into `type QID -> ancestor labels`.
     * Every requested type gets an entry, even an empty one, so the cache can
     * tell "no ancestors" from "never looked up".
     */
    internal fun parseAncestors(
        body: String,
        requested: List<String>,
    ): Map<String, List<String>> {
        val ancestors = requested.associateWith { LinkedHashSet<String>() }
        val root = json.parseToJsonElement(body).jsonObject
        val bindings = root["results"]?.jsonObject?.get("bindings")?.jsonArray
        if (bindings == null) return requested.associateWith { emptyList<String>() }
        for (element in bindings) {
            val binding = element.jsonObject
            val typeUri = binding["type"]?.jsonObject?.value("value") ?: continue
            val label = binding["ancestorLabel"]?.jsonObject?.value("value")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            ancestors[typeUri.substringAfterLast('/')]?.add(label)
        }
        return ancestors.mapValues { it.value.toList() }
    }

    /** Copies [places], storing the distinct ancestor labels of their types. */
    internal fun applyAncestors(
        places: List<NearbyPlace>,
        ancestorsByType: Map<String, List<String>>,
    ): List<NearbyPlace> = places.map { place ->
        val ancestors = place.typeQids
            .flatMap { ancestorsByType[it].orEmpty() }
            .distinct()
        if (ancestors.isEmpty()) {
            place
        } else {
            place.copy(instanceOfAncestors = ancestors.joinToString(", "))
        }
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
                   (GROUP_CONCAT(DISTINCT STR(?instanceOf); separator=" ") AS ?instanceOfQids)
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
                OPTIONAL {
                  ?instanceOf rdfs:label ?instanceOfLabel .
                  FILTER(LANG(?instanceOfLabel) = "en")
                }
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
                typeQids = binding["instanceOfQids"]?.jsonObject?.value("value")
                    ?.split(' ')
                    ?.map { it.substringAfterLast('/') }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    .orEmpty(),
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

        /** Keeps the `VALUES` list, and the GET URL that carries it, short. */
        const val MAX_TYPES_PER_ANCESTOR_REQUEST = 200

        /** Bounds the wait for the optional ancestor request. */
        const val ANCESTOR_TIMEOUT_SECONDS = 10L
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
