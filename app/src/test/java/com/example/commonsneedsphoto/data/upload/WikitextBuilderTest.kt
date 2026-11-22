package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.model.NearbyPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WikitextBuilderTest {

    private val place = NearbyPlace(
        qid = "Q123",
        label = "Old Tower",
        description = "tower in Testville",
        latitude = 44.4268,
        longitude = 26.1025,
        instanceOf = "tower",
        commonsCategory = "Old Tower",
    )

    @Test
    fun `builds a Commons information page with licence and location`() {
        val text = WikitextBuilder.build(
            place = place,
            description = "The old tower",
            author = "Jane",
            category = "Old Tower",
            license = UploadLicense.CC_BY_SA_4,
            appVersion = "2026.11.22",
            date = LocalDate.of(2026, 11, 22),
        )

        assertTrue(text.contains("== {{int:filedesc}} =="))
        assertTrue(text.contains("{{Information"))
        assertTrue(text.contains("|description=The old tower"))
        assertTrue(text.contains("{{ on Wikidata|Q123}}"))
        assertTrue(text.contains("|source={{own}}"))
        assertTrue(text.contains("|author=[[User:Jane|Jane]]"))
        assertTrue(text.contains("|date=2026-11-22"))
        assertTrue(text.contains("{{Location|44.4268|26.1025}}"))
        assertTrue(text.contains("== {{int:license-header}} =="))
        assertTrue(text.contains("{{self|cc-by-sa-4.0}}"))
        assertTrue(text.contains("{{Uploaded from Mobile|platform=Android|version=2026.11.22}}"))
        assertTrue(text.contains("[[Category:Old Tower]]"))
        assertFalse(text.contains("{{subst:unc}}"))
    }

    @Test
    fun `uses the selected licence and marks uncategorised files`() {
        val text = WikitextBuilder.build(
            place = place,
            description = "",
            author = "Jane",
            category = null,
            license = UploadLicense.CC0,
            appVersion = "1.0",
        )

        assertTrue(text.contains("|description=Old Tower"))
        assertTrue(text.contains("{{self|cc-zero}}"))
        assertTrue(text.contains("{{subst:unc}}"))
        assertFalse(text.contains("[[Category:"))
    }

    @Test
    fun `neutralises wikitext in user input`() {
        val text = WikitextBuilder.build(
            place = place,
            description = "A | tricky {{template}}",
            author = "Jane",
            category = "  Category:Old Tower | bad  ",
            license = UploadLicense.CC_BY_4,
            appVersion = "1.0",
        )

        assertTrue(text.contains("A &#124; tricky { {template} }"))
        assertTrue(text.contains("[[Category:Old Tower bad]]"))
    }
}
