package com.desmond.ofd.diag

import com.desmond.ofd.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    private val device = DeviceDiagnostics(
        model = "PLK110",
        marketName = "OnePlus 15",
        firmwareVersion = "PLK110_16.0.9.400(CN01)",
        otaBuild = "PLK110_11.A.68_0680_202606250030",
        colorOsVersion = "V16.1.0",
        ruiVersion = 7,
        androidRelease = "16",
        sdkInt = 36,
        region = "CN",
        nvId = "10010111",
    )

    private val check = CheckDiagnostics(
        appVersion = "1.6.0",
        appVersionCode = 11,
        device = device,
        backends = listOf(
            BackendDiagnostics("realme-ota", BackendDiagnostics.Status.FAIL, "HTTP 2004: Result is empty"),
            BackendDiagnostics("danielspringer.at", BackendDiagnostics.Status.OK, "PLK110_16.0.9.400(CN01)"),
            BackendDiagnostics("Mirror", BackendDiagnostics.Status.OK, "PLK110_16.0.9.400(CN01)"),
        ),
        winner = "danielspringer.at",
        installId = "4f8a1c92",
    )

    // ---- content ----

    @Test fun download_report_carries_model_and_every_backend() {
        val report = downloadDiagnostics().render("I/O after retries: Chunk 17 HTTP 403")
        assertTrue(report.contains("PLK110"))
        assertTrue(report.contains("OnePlus 15"))
        assertTrue(report.contains("PLK110_11.A.68_0680_202606250030"))
        assertTrue(report.contains("realme-ota"))
        assertTrue(report.contains("danielspringer.at"))
        assertTrue(report.contains("Mirror"))
        assertTrue(report.contains("HTTP 2004: Result is empty"))
        assertTrue(report.contains("winner: danielspringer.at"))
        assertTrue(report.contains("Chunk 17 HTTP 403"))
        assertTrue(report.contains("1.6.0 (11)"))
    }

    @Test fun check_report_stands_alone_without_a_download() {
        val report = check.render()
        assertTrue(report.contains("PLK110"))
        assertTrue(report.contains("winner: danielspringer.at"))
        assertTrue("no download section when nothing was downloaded", !report.contains("Target"))
    }

    @Test fun a_check_with_no_winner_says_so() {
        assertTrue(check.copy(winner = null).render().contains("winner: none"))
    }

    // ---- redaction ----

    @Test fun the_secondary_source_never_appears_in_a_report() {
        // The realistic leak is exception text we never composed, so redaction is a whole-string
        // pass over the finished report rather than per-field sanitising.
        val leaky = "UnknownHostException: Unable to resolve host \"${hostOfConfiguredMirror()}\"; " +
            "email=${BuildConfig.MIRROR_EMAIL} key=${BuildConfig.MIRROR_KEY} ua=${BuildConfig.MIRROR_UA}"
        val report = downloadDiagnostics().render(leaky)

        assertRedacted(report, BuildConfig.MIRROR_BASE_URL)
        assertRedacted(report, BuildConfig.MIRROR_EMAIL)
        assertRedacted(report, BuildConfig.MIRROR_KEY)
        assertRedacted(report, BuildConfig.MIRROR_UA)
        assertRedacted(report, hostOfConfiguredMirror())
    }

    @Test fun a_jwt_is_stripped_even_though_reports_should_never_contain_one() {
        // Defence in depth: the payload of a download token carries the authorized email.
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJhQGIuY29tIn0.c2ln"
        assertTrue(!redactMirrorIdentifiers("token=$jwt").contains(jwt))
    }

    @Test fun blank_secrets_leave_the_text_untouched() {
        // A clone builds with blank MIRROR_* values. `String.replace("", x)` would insert the
        // placeholder between every character and shred the whole report.
        val text = "OPlus Firmware — download failure"
        assertTrue(
            "redaction must not mangle text when nothing is configured",
            !redactMirrorIdentifiers(text).contains("<secondary-source>"),
        )
        assertEquals(text.length, redactMirrorIdentifiers(text).length.coerceAtMost(text.length))
    }

    // ---- SAF paths ----

    @Test fun saf_uri_keeps_the_provider_and_file_but_drops_the_folders() {
        val redacted = redactSafUri(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FROMs%2FPLK110.zip",
        )
        assertTrue(redacted.contains("com.android.externalstorage.documents"))
        assertTrue(redacted.contains("PLK110.zip"))
        assertTrue("the user's folder names have no diagnostic value", !redacted.contains("ROMs"))
    }

    @Test fun saf_uri_handles_odd_input() {
        assertEquals("(none)", redactSafUri(""))
        assertTrue(redactSafUri("not a uri").isNotEmpty())
    }

    @Test fun host_extraction_ignores_the_signature() {
        assertEquals(
            "gauss-compota-c-cn.allawnfs.com",
            hostOf("https://gauss-compota-c-cn.allawnfs.com/a/b.zip?Signature=secret"),
        )
        assertEquals(null, hostOf(null))
        assertEquals(null, hostOf("not a url"))
    }

    // ---- helpers ----

    private fun downloadDiagnostics() = DownloadDiagnostics(
        check = check,
        sourceLabel = "danielspringer.at / gauss-compota-c-cn.allawnfs.com",
        expectedSize = 9112442382L,
        expectedMd5 = "8fdbfd42136ac85e4a763129491a2b7f",
        targetLabel = "com.android.externalstorage.documents/…/PLK110.zip",
    )

    private fun hostOfConfiguredMirror(): String =
        hostOf(BuildConfig.MIRROR_BASE_URL) ?: ""

    private fun assertRedacted(report: String, secret: String) {
        if (secret.isBlank()) return // nothing configured in this build; nothing to leak
        assertTrue("report must not contain $secret", !report.contains(secret))
    }
}
