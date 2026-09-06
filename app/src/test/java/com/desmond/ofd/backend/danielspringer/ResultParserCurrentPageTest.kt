package com.desmond.ofd.backend.danielspringer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against the site's *current* result page, captured 2026-07-26 for OP 15 / CN.
 *
 * This layout differs from `after_post.html` in ways that broke the original parser: the
 * `<a id="downloadBtn">` anchor is gone entirely, and `<select#version>` no longer marks an
 * option `selected` — it carries `data-selected="0"` and lets JavaScript drive the combobox.
 *
 * Caveat on provenance: this was saved from a browser, so it is the post-JavaScript DOM rather
 * than the raw response the app receives. The fields asserted here are server-rendered in both
 * (`data-url`, the option list, the chips), but `#otaVersionValue` is populated by script and so
 * is *not* relied on below — it is only a later fallback in the parser.
 */
class ResultParserCurrentPageTest {

    @Test fun a_lazy_result_does_not_mistake_the_changelog_for_a_firmware_package() {
        val lazy = javaClass.getResource("/danielspringer/after_post_lazy.html")!!.readText()
        val parsed = ResultParser.parseResultHtml(lazy, 0)
        assertNull(parsed.downloadUrl)
        assertEquals("2026-08-01", parsed.securityPatch)
        assertEquals("PLK110_11.A.72_0720_202607301131", parsed.realOtaVersion)
    }

    private val html: String =
        ResultParserCurrentPageTest::class.java
            .getResource("/danielspringer/after_post_current.html")!!
            .readText(Charsets.UTF_8)

    @Test fun extracts_the_download_url_from_the_current_layout() {
        val parsed = ResultParser.parseResultHtml(html, 0)
        val url = parsed.downloadUrl
        assertNotNull("no URL found in the current result page", url)
        assertTrue("expected the CN CDN, got: $url", url!!.contains("gauss-compota-c-cn.allawnfs.com"))
        assertTrue(url.contains(".zip?"))
        assertTrue(url.contains("Expires="))
    }

    @Test fun the_download_anchor_became_a_submit_button_so_data_url_carries_the_link() {
        // #downloadBtn still exists but is now <button type="submit"> with no href, so the old
        // anchor fallback yields nothing on this page; data-url is the only source left.
        assertTrue(Regex("""<button[^>]*id="downloadBtn"""").containsMatchIn(html))
        assertTrue("no anchor carries the link any more",
            !Regex("""<a[^>]*id="downloadBtn"""").containsMatchIn(html))
        assertEquals("url: resultBox[data-url]", ResultParser.parseResultHtml(html, 0).trace.first())
    }

    @Test fun reads_the_version_even_though_the_version_select_marks_nothing_selected() {
        // The original parser looked for select#version option[selected]. Only select#device
        // still marks one, so scoping the lookup to #version matters — an unscoped search would
        // have returned the device name here.
        val versionSelect = Regex("""<select[^>]*id="version"[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)!!.groupValues[1]
        assertTrue("select#version marks no option selected",
            !Regex("""<option[^>]*\sselected""").containsMatchIn(versionSelect))
        assertEquals("PLK110_16.0.9.400(CN01)", ResultParser.parseResultHtml(html, 0).displayName)
    }

    @Test fun a_later_version_index_selects_the_matching_option() {
        assertEquals("PLK110_16.0.8.302(CN01)", ResultParser.parseResultHtml(html, 1).displayName)
        assertEquals("PLK110_16.0.8.301(CN01)", ResultParser.parseResultHtml(html, 2).displayName)
    }

    @Test fun reads_the_ota_build_and_security_patch_chips() {
        val parsed = ResultParser.parseResultHtml(html, 0)
        assertEquals("PLK110_11.A.68_0680_202606250030", parsed.realOtaVersion)
        assertEquals("2026-07-01", parsed.securityPatch)
        // A third "Region: CN" chip now sits alongside them and must not be mistaken for either.
        assertTrue(html.contains("Region: CN"))
    }

    @Test fun the_google_fonts_link_on_the_page_is_not_mistaken_for_firmware() {
        // The real hazard of a loose strategy: this page references fonts.googleapis.com, and
        // googleapis.com is an allowed firmware host for some NA builds. Only the path check
        // keeps a stylesheet from being handed to the downloader.
        assertTrue(html.contains("fonts.googleapis.com"))
        assertTrue(!ResultParser.looksLikeFirmwareUrl("https://fonts.googleapis.com/css2?family=Inter"))
    }

    @Test fun expiry_is_parsed_from_the_resolved_link() {
        val url = ResultParser.parseResultHtml(html, 0).downloadUrl!!
        val expires = ResultParser.parseExpiresEpochSeconds(url)
        assertNotNull(expires)
        assertTrue("expected an absolute epoch, got $expires", expires!! > 1_700_000_000L)
    }
}
