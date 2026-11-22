package com.example.commonsneedsphoto.data.network

import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
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
            val body = response.body?.string().orEmpty()
            parse(body)
        }
    }

    internal fun buildQuery(center: LatLon, radiusKm: Double, limit: Int): String {
        val language = Locale.getDefault().language.ifBlank { "en" }
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
                bd:serviceParam wikibase:language "$language,en" .
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
    }
}
