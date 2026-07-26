package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.VersionResolver
import com.desmond.ofd.backend.realmeota.data.Region
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DanielspringerApiTest {

    private val plk110Json: String =
        DanielspringerApiTest::class.java
            .getResource("/danielspringer/api_ota_plk110.json")!!
            .readText(Charsets.UTF_8)

    @Test fun parses_a_real_release() = runBlocking {
        val result = apiReturning { json(it, plk110Json) }.latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerApiResult.Found)
        result as DanielspringerApiResult.Found
        assertEquals("PLK110_16.0.9.400(CN01)", result.release.version)
        assertEquals("PLK110_11.A.68_0680_202606250030", result.release.otaVersion)
        assertEquals("2026-07-01", result.release.securityPatch)
        assertTrue(result.release.sourceUrl.contains("/downloadCheck"))
    }

    @Test fun version_field_is_comparable_by_VersionResolver() {
        // The whole precedence mechanism runs through VersionResolver, so an API version string
        // it cannot parse would sort below everything and this backend would never win.
        assertNotNull(VersionResolver.parse("PLK110_16.0.9.400(CN01)"))
        assertTrue(
            VersionResolver.compare("PLK110_16.0.9.400(CN01)", "PLK110_16.0.9.399(CN01)") > 0,
        )
    }

    @Test fun queries_by_model_and_asks_for_latest_only() = runBlocking {
        var seen: String? = null
        apiReturning { req ->
            seen = req.url.toString()
            json(req, plk110Json)
        }.latest("PLK110", Region.CN)
        assertTrue("expected a model filter, got: $seen", seen!!.contains("model=PLK110"))
        assertTrue("expected latest=1, got: $seen", seen!!.contains("latest=1"))
    }

    @Test fun prefers_the_release_matching_our_region() = runBlocking {
        val body = multiRegionJson()
        val result = apiReturning { json(it, body) }.latest("CPH2747", Region.EU)
        result as DanielspringerApiResult.Found
        assertEquals("EU", result.release.region)
    }

    @Test fun falls_back_to_newest_when_our_region_is_absent() = runBlocking {
        // Region is inferred from device properties and can be wrong; a mismatch must degrade to
        // "newest we know of" rather than to nothing at all.
        val result = apiReturning { json(it, multiRegionJson()) }.latest("CPH2747", Region.NA)
        result as DanielspringerApiResult.Found
        assertEquals("CPH2747_16.0.9.401(EX01)", result.release.version)
    }

    @Test fun empty_catalog_is_unusable_so_the_scrape_may_still_try() = runBlocking {
        val result = apiReturning { json(it, """{"count":0,"releases":[]}""") }
            .latest("ZZZ999", Region.GL)
        assertTrue(result is DanielspringerApiResult.Unusable)
    }

    @Test fun malformed_json_is_unusable() = runBlocking {
        val result = apiReturning { json(it, "not json at all") }.latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerApiResult.Unusable)
    }

    @Test fun server_error_is_unusable() = runBlocking {
        val result = apiReturning { json(it, "boom", code = 503) }.latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerApiResult.Unusable)
    }

    @Test fun rate_limiting_is_unreachable_so_the_scrape_is_suppressed() = runBlocking {
        // 429 is the first stage of the site's escalation to an IP-level block. Scraping in
        // response is exactly what turns the soft limit into a hard one.
        val result = apiReturning { json(it, "slow down", code = 429) }.latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerApiResult.Unreachable)
    }

    @Test fun connection_failure_is_unreachable_not_unusable() = runBlocking {
        // A blocked IP presents as a transport failure. Reading that as "try the scraper" would
        // make the app deepen its own block.
        val result = apiReturning { throw IOException("Connection refused") }
            .latest("PLK110", Region.CN)
        assertTrue(result is DanielspringerApiResult.Unreachable)
    }

    @Test fun a_304_is_served_from_cache_not_treated_as_a_parse_failure() = runBlocking {
        // A 304 has no body. Returning that empty string would look like malformed JSON, classify
        // as Unusable, and send us to the scraper — the one path that risks a hard block.
        var calls = 0
        val api = apiReturning { req ->
            calls += 1
            if (calls == 1) json(req, plk110Json, etag = "\"v1\"") else json(req, "", code = 304)
        }
        assertTrue(api.latest("PLK110", Region.CN) is DanielspringerApiResult.Found)
        val second = api.latest("PLK110", Region.CN)
        assertTrue("second call should reuse the cached body", second is DanielspringerApiResult.Found)
        second as DanielspringerApiResult.Found
        assertEquals("PLK110_16.0.9.400(CN01)", second.release.version)
    }

    @Test fun sends_if_none_match_once_an_etag_is_known() = runBlocking {
        val seen = mutableListOf<String?>()
        val api = apiReturning { req ->
            seen += req.header("If-None-Match")
            json(req, plk110Json, etag = "\"v1\"")
        }
        api.latest("PLK110", Region.CN)
        api.latest("PLK110", Region.CN)
        assertEquals(listOf(null, "\"v1\""), seen)
    }

    @Test fun blank_model_is_unusable_without_a_request() = runBlocking {
        var called = false
        val result = apiReturning { called = true; json(it, plk110Json) }.latest("", Region.CN)
        assertTrue(result is DanielspringerApiResult.Unusable)
        assertTrue("must not query the site with an empty model", !called)
    }

    // ---- helpers ----

    private fun apiReturning(block: (Request) -> Response): DanielspringerApi =
        DanielspringerApi(
            httpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain -> block(chain.request()) })
                .build(),
        )

    private fun json(
        request: Request,
        body: String,
        code: Int = 200,
        etag: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "ERR")
            .body(body.toResponseBody("application/json".toMediaType()))
        etag?.let { builder.header("ETag", it) }
        return builder.build()
    }

    private fun multiRegionJson(): String = """
        {"count":2,"releases":[
          {"model":"CPH2747","region":"EU","version":"CPH2747_16.0.9.400(EX01)",
           "ota_version":"CPH2747_11.C.40_0400_202606250030",
           "source_url":"https://component-ota-eu.allawnos.com/downloadCheck?a=1"},
          {"model":"CPH2747","region":"GLO","version":"CPH2747_16.0.9.401(EX01)",
           "ota_version":"CPH2747_11.C.41_0410_202606250030",
           "source_url":"https://component-ota-sg.allawnos.com/downloadCheck?a=2"}
        ]}
    """.trimIndent()
}
