package com.example.commonsneedsphoto.data.network

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class WikidataP18ClaimTest {

    @Test
    fun `P18 claim uses the uploaded file name as a string value`() {
        val claim = buildP18Claim("Mira Consulting Plus - 2026-11-22.jpg")
        val mainsnak = claim["mainsnak"]!!.jsonObject
        val datavalue = mainsnak["datavalue"]!!.jsonObject

        assertEquals("P18", mainsnak["property"]!!.jsonPrimitive.content)
        assertEquals("value", mainsnak["snaktype"]!!.jsonPrimitive.content)
        assertEquals("string", datavalue["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Mira Consulting Plus - 2026-11-22.jpg",
            datavalue["value"]!!.jsonPrimitive.content,
        )
        assertEquals("statement", claim["type"]!!.jsonPrimitive.content)
        assertEquals("normal", claim["rank"]!!.jsonPrimitive.content)
    }

    @Test
    fun `file name is not prefixed with File colon`() {
        val claim = buildP18Claim("Example.jpg")
        val value = claim["mainsnak"]!!.jsonObject["datavalue"]!!.jsonObject["value"]!!
            .jsonPrimitive.content

        assertEquals("Example.jpg", value)
    }
}
