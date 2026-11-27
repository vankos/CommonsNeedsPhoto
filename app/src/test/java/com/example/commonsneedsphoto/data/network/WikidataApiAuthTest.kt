package com.example.commonsneedsphoto.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises the cross-wiki authentication sequence of [WikidataApi] against a
 * local HTTP server: a normal (two-factor) Commons login has no session on
 * Wikidata, so the edit has to use one-request CentralAuth tokens.
 */
class WikidataApiAuthTest {

    private data class Call(
        val wiki: String,
        val form: Map<String, String>,
    )

    @Test
    fun `anonymous Wikidata retries the token and the edit with fresh CentralAuth tokens`() =
        withServer(localSessionWorks = false) { api, calls ->
            assertTrue(api.addImageClaim("Q108140508", "Example.jpg"))

            val csrfCalls = calls.filter { it.wiki == WIKIDATA && it.form["type"] == "csrf" }
            assertEquals(2, csrfCalls.size)
            assertNull(csrfCalls[0].form["centralauthtoken"])
            assertEquals("ca-1", csrfCalls[1].form["centralauthtoken"])

            val commonsCalls = calls.filter { it.wiki == COMMONS }
            assertEquals(2, commonsCalls.size)
            assertTrue(commonsCalls.all { it.form["action"] == "centralauthtoken" })

            val edit = calls.single { it.wiki == WIKIDATA && it.form["action"] == "wbeditentity" }
            assertEquals("ca-2", edit.form["centralauthtoken"])
            assertEquals("csrf-token", edit.form["token"])
            assertEquals("Q108140508", edit.form["id"])
            assertEditDataAddsP18(edit.form["data"])
        }

    @Test
    fun `a working Wikidata session edits without CentralAuth`() =
        withServer(localSessionWorks = true) { api, calls ->
            assertTrue(api.addImageClaim("Q108140508", "Example.jpg"))

            val edit = calls.single { it.wiki == WIKIDATA && it.form["action"] == "wbeditentity" }
            assertNull(edit.form["centralauthtoken"])
            assertTrue(calls.none { it.wiki == COMMONS })
            assertEditDataAddsP18(edit.form["data"])
        }

    @Test
    fun `an item that already has P18 is skipped before any authentication`() =
        withServer(localSessionWorks = false, hasP18 = true) { api, calls ->
            assertTrue(api.addImageClaim("Q108140508", "Example.jpg"))

            assertEquals(listOf("wbgetclaims"), calls.map { it.form["action"] })
        }

    private fun withServer(
        localSessionWorks: Boolean,
        hasP18: Boolean = false,
        block: suspend (WikidataApi, List<Call>) -> Unit,
    ) = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val centralTokens = ArrayDeque(listOf("ca-1", "ca-2"))
        val server = MockWebServer()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val wiki = if (request.path.orEmpty().startsWith("/wikidata")) WIKIDATA else COMMONS
                val form = parseForm(request.body.readUtf8())
                calls += Call(wiki, form)
                val response = when (wiki) {
                    WIKIDATA -> wikidataResponse(form, localSessionWorks, hasP18)
                    else -> commonsResponse(form, centralTokens)
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(response)
            }
        }
        server.start()

        try {
            val api = WikidataApi(
                cookieJar = CookieJar.NO_COOKIES,
                baseClient = OkHttpClient(),
                wikidataApiUrl = server.url("/wikidata").toString(),
                commonsApiUrl = server.url("/commons").toString(),
            )
            block(api, calls)
        } finally {
            server.shutdown()
        }
    }

    private fun wikidataResponse(
        form: Map<String, String>,
        localSessionWorks: Boolean,
        hasP18: Boolean,
    ): String = when (form["action"]) {
        "wbgetclaims" -> if (hasP18) {
            """{"claims":{"P18":[{"id":"Q108140508${'$'}1"}]}}"""
        } else {
            """{"claims":{}}"""
        }
        "query" -> {
            val authenticated = localSessionWorks || form["centralauthtoken"] != null
            if (authenticated) {
                """{"query":{"tokens":{"csrftoken":"csrf-token"}}}"""
            } else {
                """{"error":{"code":"assertuserfailed","info":"Anonymous users cannot edit Wikidata."}}"""
            }
        }
        "wbeditentity" -> """{"success":1}"""
        else -> """{}"""
    }

    /** The edit must add a new claim, which is impossible with `wbsetclaim`. */
    private fun assertEditDataAddsP18(data: String?) {
        val claims = Json.parseToJsonElement(data!!)
            .jsonObject["claims"]!!.jsonArray
        assertEquals(1, claims.size)
        val claim = claims.single().jsonObject
        assertNull("a new claim must not carry a GUID", claim["id"])
        val mainsnak = claim["mainsnak"]!!.jsonObject
        assertEquals("P18", mainsnak["property"]!!.jsonPrimitive.content)
        assertEquals(
            "Example.jpg",
            mainsnak["datavalue"]!!.jsonObject["value"]!!.jsonPrimitive.content,
        )
    }

    private fun commonsResponse(
        form: Map<String, String>,
        centralTokens: ArrayDeque<String>,
    ): String = if (form["action"] == "centralauthtoken") {
        val token = centralTokens.removeFirst()
        """{"centralauthtoken":{"centralauthtoken":"$token"}}"""
    } else {
        """{"error":{"code":"unexpected","info":"Unexpected request"}}"""
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { part ->
                val separator = part.indexOf('=')
                val name = if (separator < 0) part else part.substring(0, separator)
                val value = if (separator < 0) "" else part.substring(separator + 1)
                URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

    private companion object {
        const val WIKIDATA = "wikidata"
        const val COMMONS = "commons"
    }
}
