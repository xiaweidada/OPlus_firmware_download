package com.desmond.ofd.backend.mirror

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MirrorClientTest {
    @Test fun a_failed_token_renewal_never_reuses_an_expired_token() = runBlocking {
        var clock = 1_000_000L
        var tokens = 0
        var downloadRequests = 0
        val client = client(now = { clock }) { path, request ->
            when {
                path.endsWith("download-token") -> {
                    tokens++
                    if (tokens == 1) response(request, """{"token":"old-token","expires_in":120}""")
                    else response(request, "{}", 403)
                }
                path.startsWith("/api/coloros/download/") -> {
                    downloadRequests++
                    response(request, """{"full_url":"https://mirror.test/proxy"}""")
                }
                path == "/proxy" -> redirect(request, CDN_URL)
                else -> error("Unexpected request")
            }
        }
        assertTrue(client.resolveDownloadUrl("OnePlus 15", OTA) is MirrorResolution.Resolved)
        clock += 121_000
        val second = client.resolveDownloadUrl("OnePlus 15", OTA)
        assertEquals(MirrorResolution.Failed(MirrorDownloadFailure.TOKEN_REJECTED), second)
        assertEquals(1, downloadRequests)
    }

    @Test fun a_rejected_cached_token_is_replaced_once() = runBlocking {
        var tokens = 0
        val client = client { path, request ->
            when {
                path.endsWith("download-token") -> {
                    tokens++
                    response(request, """{"token":"token-$tokens","expires_in":120}""")
                }
                path.startsWith("/api/coloros/download/") -> {
                    if (request.header("X-Download-Token") == "token-1") response(request, "{}", 401)
                    else response(request, """{"full_url":"https://mirror.test/proxy"}""")
                }
                path == "/proxy" -> redirect(request, CDN_URL)
                else -> error("Unexpected request")
            }
        }
        assertEquals(MirrorResolution.Resolved(CDN_URL), client.resolveDownloadUrl("OnePlus 15", OTA))
        assertEquals(2, tokens)
    }

    @Test fun refuses_to_send_download_credentials_to_an_unrelated_proxy_host() = runBlocking {
        var externalRequests = 0
        val client = client { path, request ->
            when {
                request.url.host != "mirror.test" -> {
                    externalRequests++
                    redirect(request, CDN_URL)
                }
                path.endsWith("download-token") -> response(request, """{"token":"test-token","expires_in":120}""")
                else -> response(request, """{"full_url":"https://unrelated.test/proxy"}""")
            }
        }
        assertEquals(MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK), client.resolveDownloadUrl("OnePlus 15", OTA))
        assertEquals("credential-bearing request must stay at the configured origin", 0, externalRequests)
    }

    @Test fun rejects_a_non_firmware_proxy_destination() = runBlocking {
        val client = client { path, request ->
            when {
                path.endsWith("download-token") -> response(request, """{"token":"test-token","expires_in":120}""")
                path == "/proxy" -> redirect(request, "https://unrelated.test/login")
                else -> response(request, """{"full_url":"https://mirror.test/proxy"}""")
            }
        }
        assertEquals(MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK), client.resolveDownloadUrl("OnePlus 15", OTA))
    }

    private fun client(
        now: () -> Long = { 1_000_000L },
        respond: (String, Request) -> Response,
    ): MirrorClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = if (request.url.encodedPath.startsWith("/proxy") || request.url.host != "mirror.test") {
                request.url.encodedPath
            } else decodePath(request)
            respond(path, request)
        }.build()
        return MirrorClient(
            RuntimeEnvironment.getApplication(), baseUrl = "https://mirror.test", key = KEY,
            userAgent = "fixture-user-agent", downloadEmail = "fixture@example.test", httpClient = http, now = now,
        )
    }

    private fun decodePath(request: Request): String {
        val blob = Base64.getUrlDecoder().decode(request.url.pathSegments.last())
        val key = MessageDigest.getInstance("SHA-256").digest(KEY.toByteArray()).copyOfRange(16, 32)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(blob.copyOfRange(9, 25)))
            String(doFinal(blob.copyOfRange(25, blob.size - 32)))
        }
    }

    private fun response(request: Request, body: String, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test response")
            .body(body.toResponseBody()).build()

    private fun redirect(request: Request, location: String): Response = response(request, "", 302)
        .newBuilder().header("Location", location).build()

    private companion object {
        const val KEY = "fixture-signing-key"
        const val OTA = "PLK110_11.A.72_0720_202607301131"
        const val CDN_URL = "https://gauss-compota-c-cn.allawnfs.com/component-ota/file.zip?Signature=test"
    }
}
