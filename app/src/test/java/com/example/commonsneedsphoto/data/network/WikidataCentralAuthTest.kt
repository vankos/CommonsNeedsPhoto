package com.example.commonsneedsphoto.data.network

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WikidataCentralAuthTest {

    @Test
    fun `reads the nested token returned by formatversion 2`() {
        val root = """{"centralauthtoken":{"centralauthtoken":"138aa2df8760"}}"""
            .toJsonObject()

        assertEquals("138aa2df8760", parseCentralAuthToken(root))
    }

    @Test
    fun `reads a plain token returned by older releases`() {
        val root = """{"centralauthtoken":"plain-token"}""".toJsonObject()

        assertEquals("plain-token", parseCentralAuthToken(root))
    }

    @Test
    fun `an error response has no token`() {
        val root = """{"error":{"code":"notloggedin","info":"Anonymous users cannot obtain a centralauthtoken."}}"""
            .toJsonObject()

        assertNull(parseCentralAuthToken(root))
    }

    @Test
    fun `a blank token is treated as missing`() {
        val root = """{"centralauthtoken":{"centralauthtoken":"  "}}""".toJsonObject()

        assertNull(parseCentralAuthToken(root))
    }

    private fun String.toJsonObject() =
        kotlinx.serialization.json.Json.parseToJsonElement(this).jsonObject
}
