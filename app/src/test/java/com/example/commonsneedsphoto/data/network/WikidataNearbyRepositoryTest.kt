package com.example.commonsneedsphoto.data.network

import com.example.commonsneedsphoto.data.model.LatLon
import com.example.commonsneedsphoto.data.model.NearbyPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikidataNearbyRepositoryTest {

    private val repository = WikidataNearbyRepository()

    @Test
    fun `sparql query filters items without P18 around the center`() {
        val query = repository.buildQuery(LatLon(44.4268, 26.1025), 5.0, 500)

        assertTrue(query.contains("FILTER NOT EXISTS { ?q wdt:P18 ?image }"))
        assertTrue(query.contains("Point(26.1025 44.4268)"))
        assertTrue(query.contains("wikibase:radius \"5.00\""))
        assertTrue(query.contains("LIMIT 500"))
        assertTrue(query.contains("?q wdt:P373 ?category"))
    }

    @Test
    fun `parse reads label point category and instance of`() {
        val json = """
            {
              "head": {"vars": ["q", "qLabel", "desc", "location", "commonsCategory", "instanceOfLabels"]},
              "results": {
                "bindings": [
                  {
                    "q": {"type": "uri", "value": "http://www.wikidata.org/entity/Q123"},
                    "qLabel": {"xml:lang": "en", "type": "literal", "value": "Old Tower"},
                    "desc": {"xml:lang": "en", "type": "literal", "value": "tower in Testville"},
                    "location": {"type": "literal", "value": "Point(26.1025 44.4268)"},
                    "commonsCategory": {"type": "literal", "value": "Old Tower"},
                    "instanceOfLabels": {"type": "literal", "value": "tower, building"}
                  }
                ]
              }
            }
        """.trimIndent()

        val places = repository.parse(json)

        assertEquals(1, places.size)
        val place = places.single()
        assertEquals("Q123", place.qid)
        assertEquals("Old Tower", place.label)
        assertEquals("tower in Testville", place.description)
        assertEquals(44.4268, place.latitude, 0.000001)
        assertEquals(26.1025, place.longitude, 0.000001)
        assertEquals("Old Tower", place.commonsCategory)
        assertEquals("tower, building", place.instanceOf)
    }

    @Test
    fun `parse falls back to the qid when the label is missing`() {
        val json = """
            {
              "results": {
                "bindings": [
                  {
                    "q": {"type": "uri", "value": "http://www.wikidata.org/entity/Q42"},
                    "location": {"type": "literal", "value": "Point(-0.0752 51.5055)"}
                  }
                ]
              }
            }
        """.trimIndent()

        val place = repository.parse(json).single()

        assertEquals("Q42", place.qid)
        assertEquals("Q42", place.label)
        assertNull(place.description)
        assertEquals(51.5055, place.latitude, 0.000001)
        assertEquals(-0.0752, place.longitude, 0.000001)
    }

    @Test
    fun `parse skips bindings without a valid point`() {
        val json = """
            {
              "results": {
                "bindings": [
                  {
                    "q": {"type": "uri", "value": "http://www.wikidata.org/entity/Q1"},
                    "qLabel": {"type": "literal", "value": "Broken"},
                    "location": {"type": "literal", "value": "not a point"}
                  },
                  {
                    "q": {"type": "uri", "value": "http://www.wikidata.org/entity/Q2"},
                    "qLabel": {"type": "literal", "value": "Good"},
                    "location": {"type": "literal", "value": "Point(1 2)"}
                  }
                ]
              }
            }
        """.trimIndent()

        val places = repository.parse(json)

        assertEquals(1, places.size)
        assertEquals("Q2", places.single().qid)
    }

    @Test
    fun `parseLabels follows the preferred language chain and falls back to any label`() {
        val json = """
            {
              "entities": {
                "Q108142865": {
                  "labels": {
                    "ca": {"language": "ca", "value": "Centre Priv. Ed. Inf. 1er Cicle Ruzafa Montessori"},
                    "es": {"language": "es", "value": "Centro Privado de Educacion Infantil de Primer Ciclo Ruzafa Montessori"}
                  }
                },
                "Q42": {
                  "labels": {
                    "en": {"language": "en", "value": "Douglas Adams"},
                    "mul": {"language": "mul", "value": "Douglas Adams (mul)"}
                  }
                },
                "Q1": {
                  "labels": {
                    "es": {"language": "es", "value": "Nombre"},
                    "mul": {"language": "mul", "value": "Neutral name"}
                  }
                },
                "Q7": {
                  "labels": {
                    "en": {"language": "en", "value": "English name"},
                    "ru": {"language": "ru", "value": "Русское название"}
                  }
                }
              }
            }
        """.trimIndent()

        val labels = repository.parseLabels(json, listOf("ru", "en", "mul"))

        assertEquals(
            "Centre Priv. Ed. Inf. 1er Cicle Ruzafa Montessori",
            labels["Q108142865"],
        )
        assertEquals("Douglas Adams", labels["Q42"])
        assertEquals("Neutral name", labels["Q1"])
        assertEquals("Русское название", labels["Q7"])
    }

    @Test
    fun `parseLabels skips items without a usable label`() {
        val json = """
            {
              "entities": {
                "Q1": {"labels": {}},
                "Q2": {"labels": {"es": {"language": "es", "value": "  "}}},
                "Q3": {}
              }
            }
        """.trimIndent()

        assertTrue(repository.parseLabels(json, listOf("ru", "en", "mul")).isEmpty())
    }

    @Test
    fun `fallback labels replace only qid placeholders`() {
        val missing = NearbyPlace(
            qid = "Q108142865",
            label = "Q108142865",
            description = null,
            latitude = 39.459822,
            longitude = -0.376108,
            instanceOf = null,
            commonsCategory = null,
        )
        val named = missing.copy(qid = "Q42", label = "Douglas Adams")

        val places = applyFallbackLabels(
            listOf(missing, named),
            mapOf(
                "Q108142865" to "Centro Privado de Educacion Infantil de Primer Ciclo Ruzafa Montessori",
                "Q42" to "ignored",
            ),
        )

        assertEquals(
            "Centro Privado de Educacion Infantil de Primer Ciclo Ruzafa Montessori",
            places[0].label,
        )
        assertEquals("Douglas Adams", places[1].label)
    }
}
