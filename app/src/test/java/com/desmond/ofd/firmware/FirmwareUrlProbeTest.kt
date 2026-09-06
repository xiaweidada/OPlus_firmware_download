package com.desmond.ofd.firmware

import com.desmond.ofd.http.FIRMWARE_USER_AGENT
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareUrlProbeTest {

    @Test fun recovers_daniel_gate_404_on_the_official_automatic_cdn() = runBlocking {
        val seen = mutableListOf<String>()
        val client = clientReturning { request ->
            seen += request.url.toString()
            when (request.url.host) {
                "component-ota-cn.allawntech.com" -> response(
                    request, 302, headers = mapOf("Location" to MANUAL_URL),
                )
                "gauss-compota-c-cn.allawnfs.com" -> response(request, 404, message = "Not Found")
                "gauss-compotaauto-c-cn.allawnfs.com" -> response(
                    request, 206, headers = mapOf(
                        "Content-Range" to "bytes 0-0/$PLK110_SIZE",
                        "x-amz-meta-filemd5" to PLK110_MD5,
                    ),
                )
                else -> error("Unexpected request")
            }
        }

        val result = FirmwareUrlProbe(client).probe(
            "https://component-ota-cn.allawntech.com/downloadCheck?id=plk110",
            expectedSize = PLK110_SIZE,
        )

        assertTrue("The catalog gate must yield a downloadable file: $result", result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(AUTOMATIC_URL, result.resolvedUrl)
        assertEquals(PLK110_MD5, result.md5)
        assertEquals(listOf(MANUAL_URL, AUTOMATIC_URL), seen.drop(1))
    }

    @Test fun recovers_a_mirror_cdn_link_without_changing_its_path_or_signature() = runBlocking {
        val seen = mutableListOf<String>()
        val client = clientReturning { request ->
            seen += request.url.toString()
            if (request.url.toString() == MANUAL_URL) {
                response(request, 404, message = "Not Found")
            } else {
                response(request, 206, headers = mapOf("Content-Range" to "bytes 0-0/$PLK110_SIZE"))
            }
        }
        val result = FirmwareUrlProbe(client).probe(MANUAL_URL, PLK110_SIZE)
        assertTrue(result is FirmwareUrlProbeResult.Success)
        assertEquals(listOf(MANUAL_URL, AUTOMATIC_URL), seen)
    }

    @Test fun a_working_primary_cdn_does_not_request_the_backup() = runBlocking {
        var requests = 0
        val client = clientReturning { request ->
            requests++
            response(request, 206, headers = mapOf("Content-Range" to "bytes 0-0/$PLK110_SIZE"))
        }
        val result = FirmwareUrlProbe(client).probe(MANUAL_URL, PLK110_SIZE)
        assertEquals(MANUAL_URL, (result as FirmwareUrlProbeResult.Success).resolvedUrl)
        assertEquals(1, requests)
    }

    @Test fun backup_must_match_the_catalog_size() = runBlocking {
        var requests = 0
        val client = clientReturning { request ->
            requests++
            if (requests == 1) response(request, 404) else response(
                request, 206, headers = mapOf("Content-Range" to "bytes 0-0/${PLK110_SIZE + 1}"),
            )
        }
        val result = FirmwareUrlProbe(client).probe(MANUAL_URL, PLK110_SIZE)
        assertEquals(2, requests)
        assertTrue(result is FirmwareUrlProbeResult.Failure)
        assertTrue((result as FirmwareUrlProbeResult.Failure).detail.contains("Size mismatch"))
    }

    @Test fun backup_404_is_bounded_and_preserves_the_http_error() = runBlocking {
        var requests = 0
        val client = clientReturning { request -> requests++; response(request, 404, message = "Not Found") }
        val result = FirmwareUrlProbe(client).probe(MANUAL_URL, PLK110_SIZE)
        assertEquals(2, requests)
        assertEquals(404, (result as FirmwareUrlProbeResult.Failure).httpCode)
    }

    @Test fun does_not_rewrite_other_hosts_or_an_authentication_failure() = runBlocking {
        for ((url, status) in listOf(
            MANUAL_URL.replace("allawnfs.com", "allawnfs.com.example.org") to 404,
            MANUAL_URL.replace(".zip?", ".html?") to 404,
            MANUAL_URL to 403,
        )) {
            var requests = 0
            val client = clientReturning { request -> requests++; response(request, status) }
            assertTrue(FirmwareUrlProbe(client).probe(url) is FirmwareUrlProbeResult.Failure)
            assertEquals(1, requests)
        }
    }

    @Test fun rejects_a_content_range_for_the_wrong_requested_byte() = runBlocking {
        val client = clientReturning { request ->
            response(request, 206, headers = mapOf("Content-Range" to "bytes 1-1/$TWO_GIB"))
        }
        assertTrue(FirmwareUrlProbe(client).probe("https://example.com/file.zip") is FirmwareUrlProbeResult.Failure)
    }

    @Test fun succeeds_from_content_range() = runBlocking {
        var userAgent: String? = null
        val client = clientReturning { request ->
            userAgent = request.header("User-Agent")
            response(
                request = request,
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes 0-0/$TWO_GIB",
                    "x-amz-meta-filemd5" to "abc123",
                ),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(TWO_GIB, result.totalSize)
        assertEquals("abc123", result.md5)
        assertEquals(FIRMWARE_USER_AGENT, userAgent)
    }

    @Test fun rejects_small_content_range_as_retryable_failure() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-0/49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(49L, result.observedSize)
        assertTrue(result.retryable)
    }

    @Test fun rejects_small_content_length_as_retryable_failure() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to "49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(49L, result.observedSize)
        assertTrue(result.retryable)
        assertEquals(null, result.rejectionCode)
    }

    @Test fun parses_antileech_response_code_from_small_body() = runBlocking {
        val rejection = """{"body":null,"errMsg":"2306","responseCode":2306}"""
        val client = clientReturning { request ->
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to rejection.length.toString()),
                body = rejection,
                bodyMediaType = "application/json",
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(rejection.length.toLong(), result.observedSize)
        assertEquals("2306", result.rejectionCode)
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("2306"))
    }

    @Test fun resolves_download_check_gate_before_range_probe() = runBlocking {
        val finalUrl = "https://gauss-compotaauto-cn.allawnfs.com/component-ota/file.zip"
        val seen = mutableListOf<Pair<String, String?>>()
        val client = clientReturning { request ->
            seen += request.url.encodedPath to request.header("Range")
            when (request.url.encodedPath) {
                "/downloadCheck" -> response(
                    request = request,
                    code = 302,
                    message = "Found",
                    headers = mapOf("Location" to finalUrl),
                    body = "",
                )
                "/component-ota/file.zip" -> response(
                    request = request,
                    code = 206,
                    headers = mapOf("Content-Range" to "bytes 0-0/$TWO_GIB"),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val result = FirmwareUrlProbe(client).probe(
            "https://component-ota-cn.allawntech.com/downloadCheck?id=abc",
        )

        assertTrue(result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(finalUrl, result.resolvedUrl)
        assertEquals(TWO_GIB, result.totalSize)
        assertEquals(listOf("/downloadCheck" to null, "/component-ota/file.zip" to "bytes=0-0"), seen)
    }

    @Test fun reports_download_check_antileech_without_range_probe() = runBlocking {
        val rejection = """{"errMsg":"2306","responseCode":2306}"""
        var rangeHeader: String? = "not-called"
        val client = clientReturning { request ->
            rangeHeader = request.header("Range")
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to rejection.length.toString()),
                body = rejection,
                bodyMediaType = "application/json",
            )
        }

        val result = FirmwareUrlProbe(client).probe(
            "https://component-ota-cn.allawntech.com/downloadCheck?id=abc",
        )

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(null, rangeHeader)
        assertEquals("2306", result.rejectionCode)
        assertTrue(result.detail.contains("2306"))
    }

    @Test fun rejects_invalid_content_range() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes */49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("Content-Range"))
    }

    @Test fun treats_forbidden_as_retryable_url_failure() = runBlocking {
        val client = clientReturning { request ->
            response(request = request, code = 403, message = "Forbidden")
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(403, result.httpCode)
        assertTrue(result.retryable)
    }

    @Test fun treats_not_found_as_non_retryable_url_failure() = runBlocking {
        val client = clientReturning { request ->
            response(request = request, code = 404, message = "Not Found")
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(404, result.httpCode)
        assertTrue(!result.retryable)
    }

    @Test fun rejects_expected_size_mismatch() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-0/$TWO_GIB"),
            )
        }

        val result = FirmwareUrlProbe(client).probe(
            url = "https://example.com/firmware.zip",
            expectedSize = TWO_GIB + 1,
        )

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(TWO_GIB, result.observedSize)
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("Size mismatch"))
    }

    @Test fun rejects_invalid_url_without_retry() = runBlocking {
        val result = FirmwareUrlProbe(OkHttpClient()).probe("not a url")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertTrue(!result.retryable)
        assertTrue(result.detail.contains("Invalid download URL"))
    }

    private fun clientReturning(block: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> block(chain.request()) })
            .build()

    private fun response(
        request: Request,
        code: Int,
        message: String = "OK",
        headers: Map<String, String> = emptyMap(),
        body: String = "x",
        bodyMediaType: String = "text/plain",
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody(bodyMediaType.toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private companion object {
        private const val TWO_GIB = 2L * 1024L * 1024L * 1024L
        private const val PLK110_SIZE = 9146672362L
        private const val PLK110_MD5 = "2cbe1af4dece932fff62c81f5e6dbb68"
        // Sanitized version of the URL returned by both live backends for PLK110 16.0.10.500.
        private const val MANUAL_URL = "https://gauss-compota-c-cn.allawnfs.com/remove-catalog/g-group/" +
            "component-ota/26/07/31/3995f394e49c49f687b1bfdad15a1e71.zip?Expires=1999999999&Signature=a%2Bb%2Fc%3D"
        private val AUTOMATIC_URL = MANUAL_URL.replace("gauss-compota-c-cn.", "gauss-compotaauto-c-cn.")
    }
}
