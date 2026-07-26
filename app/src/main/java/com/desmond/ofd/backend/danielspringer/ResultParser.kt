package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.firmware.parseFirmwareUrlExpiresEpochSeconds
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parses the page danielspringer returns after the OTA form is submitted.
 *
 * Written to tolerate the markup changing, because it has and will again — the site recently
 * moved its selects behind JavaScript comboboxes. Each field is therefore attempted by several
 * strategies in descending order of confidence.
 *
 * The failure mode worth defending against is **not** "no strategy matched" — that is easy to
 * see and safe. It is a loose strategy matching *junk*: an error page, a captcha, or an unrelated
 * link, yielding a plausible-looking URL that only fails much later during a download. So every
 * candidate URL must survive [looksLikeFirmwareUrl] before it is accepted, and a page with
 * nothing firmware-shaped on it yields null rather than a guess.
 *
 * TODO: calibrate against a real post-submit page. The strategies here were written against the
 * *previous* layout plus the current form page, because the site now answers the POST with 429
 * from any address that has scraped it recently — including a fresh session sending a single
 * request — so no current sample could be captured. What is verified is the rejection behaviour
 * (a 429 page, a captcha, or unrelated links all yield null); what is unverified is whether any
 * strategy still *matches* on the new markup. Capture one result page from an address the site
 * has not seen — a phone on cellular works — and pin it as a fixture in [ResultParserTest].
 * Low urgency: this is the fallback, and the JSON API path in [DanielspringerApi] is unaffected.
 */
object ResultParser {

    data class Parsed(
        val downloadUrl: String?,
        val displayName: String?,
        val realOtaVersion: String?,
        val securityPatch: String?,
        val manualOnly: Boolean,
        /**
         * Which strategies ran and how they fared, newest markup first. Surfaced in diagnostics so
         * the first user to hit a changed page reports back the shape rather than just "failed".
         */
        val trace: List<String> = emptyList(),
    )

    /** Hosts OPPO and Google actually serve firmware from. Anything else is not a download link. */
    private val ALLOWED_HOST_SUFFIXES = listOf(
        "allawnfs.com",    // CN CDN
        "allawnofs.com",   // EU / GL / IN CDN
        "allawntech.com",  // CN downloadCheck gate
        "allawnos.com",    // EU / GL / IN downloadCheck gate
        "googleapis.com",  // some NA builds
        "gvt1.com",
    )

    fun parseResultHtml(html: String, versionIndex: Int): Parsed {
        val doc = Jsoup.parse(html)
        val trace = mutableListOf<String>()

        val downloadUrl = extractUrl(doc, html, trace)
        val displayName = extractDisplayName(doc, versionIndex, trace)
        val chips = extractChips(doc)

        return Parsed(
            downloadUrl = downloadUrl,
            displayName = displayName,
            realOtaVersion = chips.firstOrNull { OTA_TIMESTAMP_RE.containsMatchIn(it) }
                ?: OTA_TIMESTAMP_TEXT_RE.find(doc.text())?.value,
            securityPatch = chips
                .firstOrNull { it.startsWith("Sec. Patch:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
                ?: SEC_PATCH_RE.find(doc.text())?.groupValues?.get(1),
            manualOnly = chips.any { it.equals("manual-only", ignoreCase = true) },
            trace = trace,
        )
    }

    /**
     * A URL is only accepted as a firmware link when it is absolute HTTPS, points at a host we
     * know serves firmware, and looks like either a package or the anti-leech gate. Without this
     * every strategy below would happily return the site's own stylesheet or a donate link.
     */
    internal fun looksLikeFirmwareUrl(candidate: String?): Boolean {
        val url = candidate?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return false
        if (url.scheme != "https") return false
        val host = url.host.lowercase()
        if (ALLOWED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) return false
        val path = url.encodedPath.lowercase()
        return path.endsWith(".zip") || path.endsWith("/downloadcheck") || path.contains("/component-ota/")
    }

    private fun extractUrl(doc: Document, html: String, trace: MutableList<String>): String? {
        // 1. Canonical: the result box carries the URL as a data attribute in every layout so far.
        doc.selectFirst("#resultBox[data-url]")?.attr("data-url")?.let { candidate ->
            if (looksLikeFirmwareUrl(candidate)) {
                trace += "url: resultBox[data-url]"
                return candidate.trim()
            }
            trace += "url: resultBox[data-url] present but not firmware-shaped"
        } ?: run { trace += "url: no resultBox[data-url]" }

        // 2. The visible button, present only in the OPPO-CDN layout.
        doc.selectFirst("a#downloadBtn[href]")?.attr("href")?.let { candidate ->
            if (looksLikeFirmwareUrl(candidate)) {
                trace += "url: a#downloadBtn"
                return candidate.trim()
            }
        }

        // 3. Any element that carries a firmware-shaped URL in any attribute. Survives the id or
        //    class being renamed, which is the most likely way this page changes.
        for (element in doc.allElements) {
            for (attribute in element.attributes()) {
                if (looksLikeFirmwareUrl(attribute.value)) {
                    trace += "url: <${element.tagName()} ${attribute.key}>"
                    return attribute.value.trim()
                }
            }
        }

        // 4. Raw text scan, for a URL that never made it into an attribute at all.
        URL_IN_TEXT_RE.findAll(html)
            .map { it.value.trimEnd('"', '\'', '<', ')', ',', ';') }
            .firstOrNull(::looksLikeFirmwareUrl)
            ?.let {
                trace += "url: raw text scan"
                return it
            }

        trace += "url: no firmware-shaped URL anywhere on the page"
        return null
    }

    private fun extractDisplayName(doc: Document, versionIndex: Int, trace: MutableList<String>): String? {
        doc.selectFirst("select#version option[selected]")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { trace += "name: option[selected]"; return it }

        doc.selectFirst("select#version option[value=$versionIndex]")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { trace += "name: option[value]"; return it }

        // The JS combobox mirrors the chosen version into its trigger label.
        doc.selectFirst("#otaVersionValue")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() && VERSION_NAME_RE.containsMatchIn(it) }
            ?.let { trace += "name: #otaVersionValue"; return it }

        VERSION_NAME_RE.find(doc.text())?.value?.let { trace += "name: text scan"; return it }

        trace += "name: not found"
        return null
    }

    /**
     * Chip text, without depending on the `ota-chip` class name surviving. Anything whose class
     * merely contains "chip" counts, and callers fall back to scanning page text.
     */
    private fun extractChips(doc: Document): List<String> =
        doc.select(".ota-chip, [class*=chip]").map { it.text().trim() }.filter { it.isNotEmpty() }

    /** Backwards-compat shims used by older tests. */
    fun extractDownloadUrl(html: String): String? = parseResultHtml(html, 0).downloadUrl

    fun extractSelectedVersionName(html: String, versionIndex: Int): String? =
        parseResultHtml(html, versionIndex).displayName

    fun parseExpiresEpochSeconds(url: String): Long? = parseFirmwareUrlExpiresEpochSeconds(url)

    private val OTA_TIMESTAMP_RE = Regex("""_\d{12}\b""")
    private val OTA_TIMESTAMP_TEXT_RE = Regex("""\b[A-Z0-9]+_\d{2}\.[A-Z]\.\d+_\d+_\d{12}\b""")
    private val SEC_PATCH_RE = Regex("""Sec\.?\s*Patch:?\s*(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)
    private val VERSION_NAME_RE = Regex("""\b[A-Z]{2,3}\d{3,5}_\d+(?:\.\d+)+\([A-Z]{2}\d{2}\)""")
    private val URL_IN_TEXT_RE = Regex("""https://[^\s"'<>\\]+""")
}
