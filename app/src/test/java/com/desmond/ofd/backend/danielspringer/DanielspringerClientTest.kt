package com.desmond.ofd.backend.danielspringer

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class DanielspringerClientTest {
    @Test fun resolves_the_lazy_page_in_the_same_cookie_session_then_verifies_the_cdn() = runBlocking {
        val page = javaClass.getResource("/danielspringer/after_post_lazy.html")!!.readText()
        val failure = AtomicReference<Throwable>()
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/index.php") { exchange ->
            try {
                val query = exchange.requestURI.rawQuery.orEmpty()
                calls += exchange.requestMethod + " " + query
                val body = when {
                    exchange.requestMethod == "GET" && "k=selected" !in query -> {
                        exchange.responseHeaders.add("Set-Cookie", "PHPSESSID=test-session; Path=/; HttpOnly")
                        "<form></form>"
                    }
                    "ota_action=resolve_json" in query -> {
                        assertEquals("PHPSESSID=test-session", exchange.requestHeaders.getFirst("Cookie"))
                        assertEquals("k=test-selection&csrf=test-csrf", exchange.requestBody.reader().readText())
                        """{"ok":true,"url":"$MANUAL_URL","manual":false}"""
                    }
                    exchange.requestMethod == "POST" -> {
                        assertEquals("PHPSESSID=test-session", exchange.requestHeaders.getFirst("Cookie"))
                        exchange.requestBody.close()
                        exchange.responseHeaders.add("Location", "/index.php?view=ota&k=selected")
                        exchange.sendResponseHeaders(302, -1)
                        null
                    }
                    else -> page
                }
                if (body != null) {
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
            } catch (t: Throwable) {
                failure.set(t)
                exchange.sendResponseHeaders(500, -1)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "127.0.0.1") chain.proceed(request)
                else {
                    val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                        .body("x".toResponseBody()).message("Test CDN")
                    if (request.url.host == "gauss-compota-c-cn.allawnfs.com") response.code(404)
                    else response.code(206).header("Content-Range", "bytes 0-0/9146672362")
                        .header("x-amz-meta-filemd5", "2cbe1af4dece932fff62c81f5e6dbb68")
                    response.build()
                }
            }.build()
            val result = DanielspringerClient(http, "http://127.0.0.1:${server.address.port}")
                .fetchLatestUrl("OP 15", "CN")
            failure.get()?.let { throw it }
            assertEquals("PLK110_16.0.10.500(CN01)", result.displayName)
            assertEquals("2026-08-01", result.securityPatch)
            assertTrue(result.downloadUrl.startsWith("https://gauss-compotaauto-c-cn.allawnfs.com/"))
            assertEquals(4, calls.size)
            assertEquals("POST view=ota&ota_action=resolve_json", calls.last())
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val MANUAL_URL = "https://gauss-compota-c-cn.allawnfs.com/component-ota/file.zip?Expires=1999999999&Signature=test"
    }
}
