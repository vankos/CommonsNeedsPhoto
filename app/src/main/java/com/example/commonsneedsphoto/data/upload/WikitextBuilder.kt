package com.example.commonsneedsphoto.data.upload

import com.example.commonsneedsphoto.data.model.NearbyPlace
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Builds the wikitext of a Commons file page. The layout follows the
 * {{Information}} template used by the original Commons Android app.
 */
object WikitextBuilder {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun build(
        place: NearbyPlace,
        description: String,
        author: String,
        category: String?,
        license: UploadLicense,
        appVersion: String,
        date: LocalDate = LocalDate.now(),
    ): String {
        val safeDescription = sanitizeInline(description).ifBlank { place.label }
        val safeAuthor = sanitizeInline(author).ifBlank { "Unknown" }
        val builder = StringBuilder()
        builder.appendLine("== {{int:filedesc}} ==")
        builder.appendLine("{{Information")
        builder.appendLine("|description=$safeDescription")
        builder.appendLine("{{ on Wikidata|${place.qid}}}")
        builder.appendLine("|source={{own}}")
        builder.appendLine("|author=[[User:$safeAuthor|$safeAuthor]]")
        builder.appendLine("|date=${date.format(dateFormatter)}")
        builder.appendLine("}}")
        builder.appendLine()
        builder.appendLine("{{Location|${place.latitude}|${place.longitude}}}")
        builder.appendLine()
        builder.appendLine("== {{int:license-header}} ==")
        builder.appendLine(license.template)
        builder.appendLine()
        builder.appendLine("{{Uploaded from Mobile|platform=Android|version=$appVersion}}")
        val safeCategory = sanitizeCategory(category)
        if (safeCategory != null) {
            builder.appendLine()
            builder.appendLine("[[Category:$safeCategory]]")
        } else {
            builder.appendLine("{{subst:unc}}")
        }
        return builder.toString()
    }

    fun sanitizeInline(value: String): String = value
        .replace("{{", "{ {")
        .replace("}}", "} }")
        .replace("|", "&#124;")
        .replace("\r", " ")
        .replace("\n", " ")
        .trim()

    fun sanitizeCategory(value: String?): String? {
        var cleaned = value?.trim() ?: return null
        cleaned = cleaned
            .removePrefix("Category:")
            .removePrefix("category:")
            .replace("[", "")
            .replace("]", "")
            .replace("|", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }
}

object CommonsFileName {

    private val invalidChars = Regex("""[\\/:*?"<>|#\[\]{}]""")

    /**
     * @return a MediaWiki-safe file title ending with .jpg
     */
    fun sanitize(input: String, fallback: String): String {
        var name = input.trim().removePrefix("File:").trim()
        name = invalidChars.replace(name, " ")
        name = name.replace(Regex("\\s+"), " ").trim().trim('.')
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("jpg", "jpeg", "png", "webp", "heic")) {
            name = name.substringBeforeLast('.')
        }
        if (name.isBlank()) name = fallback
        name = name.take(170).trim().trim('.')
        if (name.isBlank()) name = "Commons photo"
        return "$name.jpg"
    }

    fun defaultName(place: NearbyPlace, date: LocalDate = LocalDate.now()): String {
        val label = place.label.takeIf { it.isNotBlank() && it != place.qid } ?: place.qid
        return sanitize(
            "$label - ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}",
            fallback = "${place.qid} photo",
        )
    }

    fun withSuffix(fileName: String, suffix: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) {
            "${fileName.substring(0, dot)} ($suffix)${fileName.substring(dot)}"
        } else {
            "$fileName ($suffix)"
        }
    }
}
