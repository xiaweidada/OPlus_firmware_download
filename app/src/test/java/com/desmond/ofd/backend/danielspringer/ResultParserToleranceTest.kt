package com.desmond.ofd.backend.danielspringer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is written against markup that changes, so the risk is not "no strategy matched" —
 * that is visible and safe — but a loose strategy matching junk and producing a plausible URL
 * that only fails later, mid-download. These tests pin the rejection behaviour.
 */
class ResultParserToleranceTest {

    private val cdnUrl =
        "https://gauss-compotaauto-c-cn.allawnfs.com/remove-x/component-ota/26/05/06/pkg.zip" +
            "?sign=a&t=b&AWSAccessKeyId=k&Expires=1778275440&Signature=s"

    @Test fun accepts_known_cdn_and_gate_hosts() {
        assertTrue(ResultParser.looksLikeFirmwareUrl(cdnUrl))
        assertTrue(
            ResultParser.looksLikeFirmwareUrl(
                "https://component-ota-cn.allawntech.com/downloadCheck?c=1&p=2",
            ),
        )
        assertTrue(
            ResultParser.looksLikeFirmwareUrl(
                "https://component-ota-eu.allawnos.com/downloadCheck?c=1",
            ),
        )
    }

    @Test fun rejects_urls_that_are_not_firmware() {
        assertTrue(!ResultParser.looksLikeFirmwareUrl("https://roms.danielspringer.at/style.css"))
        assertTrue(!ResultParser.looksLikeFirmwareUrl("https://paypal.me/someone"))
        assertTrue(!ResultParser.looksLikeFirmwareUrl("http://gauss-compotaauto-c-cn.allawnfs.com/a.zip"))
        assertTrue(!ResultParser.looksLikeFirmwareUrl("/relative/path.zip"))
        assertTrue(!ResultParser.looksLikeFirmwareUrl(""))
        assertTrue(!ResultParser.looksLikeFirmwareUrl(null))
    }

    @Test fun rejects_a_lookalike_host_suffix() {
        // "evil-allawnfs.com" must not pass a suffix check for "allawnfs.com".
        assertTrue(!ResultParser.looksLikeFirmwareUrl("https://evil-allawnfs.com/a/pkg.zip"))
    }

    @Test fun a_page_with_only_unrelated_links_yields_null() {
        val html = """
            <html><body>
              <a href="https://example.com/download.zip">Download</a>
              <a href="https://roms.danielspringer.at/donate">Donate</a>
            </body></html>
        """.trimIndent()
        assertNull(ResultParser.parseResultHtml(html, 0).downloadUrl)
    }

    @Test fun a_rate_limit_page_yields_null_rather_than_a_guess() {
        val html = """
            <!doctype html><html><head><title>Too many requests</title></head>
            <body><main><p class="code">Error 429</p><h1>Too many requests</h1>
            <a href="/">Back to the archive</a></main></body></html>
        """.trimIndent()
        val parsed = ResultParser.parseResultHtml(html, 0)
        assertNull(parsed.downloadUrl)
        assertNull(parsed.displayName)
    }

    @Test fun finds_the_url_when_the_result_box_is_renamed() {
        // Strategy 3: any attribute carrying a firmware-shaped URL, for when ids/classes change.
        val html = """<html><body><div id="somethingNew" data-href="$cdnUrl"></div></body></html>"""
        assertEquals(cdnUrl, ResultParser.parseResultHtml(html, 0).downloadUrl)
    }

    @Test fun finds_the_url_in_raw_text_as_a_last_resort() {
        val html = "<html><body><pre>curl -O '$cdnUrl'</pre></body></html>"
        assertEquals(cdnUrl, ResultParser.parseResultHtml(html, 0).downloadUrl)
    }

    @Test fun reads_the_version_from_the_new_js_combobox() {
        val html = """
            <html><body>
              <span id="otaVersionValue">PLK110_16.0.9.400(CN01)</span>
              <div id="resultBox" data-url="$cdnUrl"></div>
            </body></html>
        """.trimIndent()
        assertEquals("PLK110_16.0.9.400(CN01)", ResultParser.parseResultHtml(html, 0).displayName)
    }

    @Test fun ignores_a_combobox_placeholder() {
        // The trigger reads "Choose a version…" until something is picked; that is not a version.
        val html = """<html><body><span id="otaVersionValue">Choose a version…</span></body></html>"""
        assertNull(ResultParser.parseResultHtml(html, 0).displayName)
    }

    @Test fun reads_chips_without_depending_on_the_ota_chip_class() {
        val html = """
            <html><body>
              <div id="resultBox" data-url="$cdnUrl"></div>
              <span class="badge-chip">PLK110_11.A.63_0630_202605061316</span>
              <span class="badge-chip">Sec. Patch: 2026-05-01</span>
            </body></html>
        """.trimIndent()
        val parsed = ResultParser.parseResultHtml(html, 0)
        assertEquals("PLK110_11.A.63_0630_202605061316", parsed.realOtaVersion)
        assertEquals("2026-05-01", parsed.securityPatch)
    }

    @Test fun trace_records_what_was_tried() {
        // Surfaced in diagnostics so the first user to hit a changed page reports the shape.
        val parsed = ResultParser.parseResultHtml("<html><body>nothing</body></html>", 0)
        assertTrue(parsed.trace.isNotEmpty())
        assertNotNull(parsed.trace.firstOrNull { it.startsWith("url:") })
    }

    @Test fun catalog_parse_degrades_instead_of_throwing() {
        // A markup change should read as "model not listed", not as an exception every caller
        // has to remember to catch.
        assertEquals(0, DanielspringerCatalog.parse("<html><body>no form here</body></html>").modelCount)
        assertEquals(0, DanielspringerCatalog.parse("""<select id="device" data-devices=""></select>""").modelCount)
        assertEquals(
            0,
            DanielspringerCatalog.parse("""<select id="device" data-devices="{bad json"></select>""").modelCount,
        )
    }
}
