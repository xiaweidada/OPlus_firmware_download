package com.desmond.ofd.firmware

import com.desmond.ofd.http.FIRMWARE_ID_HEADER
import com.desmond.ofd.http.FIRMWARE_ID_VALUE
import com.desmond.ofd.http.FIRMWARE_USER_AGENT
import com.desmond.ofd.http.await
import com.desmond.ofd.http.parseContentRange
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class FirmwareUrlProbe(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    suspend fun probe(
        url: String,
        expectedSize: Long = -1L,
        tag: Any? = null,
        expectedMd5: String? = null,
    ): FirmwareUrlProbeResult {
        return try {
            val resolvedUrl = when (val gate = FirmwareDownloadGate.resolve(url, httpClient, tag)) {
                is FirmwareDownloadGateResult.Success -> gate.resolvedUrl
                is FirmwareDownloadGateResult.Failure -> return FirmwareUrlProbeResult.Failure(
                    detail = gate.detail,
                    retryable = gate.retryable,
                    httpCode = gate.httpCode,
                    rejectionCode = gate.rejectionCode,
                )
            }
            val (result, cdnUrl) = probeCdn(resolvedUrl, expectedSize, expectedMd5, tag)
            if (result !is FirmwareUrlProbeResult.Failure || result.httpCode != 404) return result
            val fallback = automaticCdnFallback(cdnUrl) ?: return result
            // Some CN full packages have left the manual bucket but still exist on the
            // automatic CDN. Keep the exact object path and signature, and verify that response
            // too. A second 404 ends the attempt; it must never become a successful size probe.
            probeCdn(fallback.toString(), expectedSize, expectedMd5, tag).first
        } catch (e: IOException) {
            FirmwareUrlProbeResult.Failure(
                detail = "Network probe failed: ${e.message ?: e::class.simpleName ?: "IOException"}",
                retryable = true,
            )
        } catch (e: IllegalArgumentException) {
            FirmwareUrlProbeResult.Failure(
                detail = "Invalid download URL: ${e.message ?: "(no message)"}",
                retryable = false,
            )
        }
    }

    private suspend fun probeCdn(
        url: String,
        expectedSize: Long,
        expectedMd5: String?,
        tag: Any?,
    ): Pair<FirmwareUrlProbeResult, HttpUrl> {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header(FIRMWARE_ID_HEADER, FIRMWARE_ID_VALUE)
            .header("User-Agent", FIRMWARE_USER_AGENT)
            .header("Accept", "*/*")
        if (tag != null) builder.tag(tag)
        return httpClient.newCall(builder.build()).await().use { response ->
            parseResponse(response, expectedSize, expectedMd5) to response.request.url
        }
    }

    private fun automaticCdnFallback(url: HttpUrl): HttpUrl? {
        if (url.scheme != "https" || url.port != 443 ||
            url.host != "gauss-compota-c-cn.allawnfs.com" ||
            "component-ota" !in url.pathSegments || !url.encodedPath.endsWith(".zip")
        ) return null
        return url.newBuilder().host("gauss-compotaauto-c-cn.allawnfs.com").build()
    }

    private fun parseResponse(resp: Response, expectedSize: Long, expectedMd5: String?): FirmwareUrlProbeResult {
        var rejectionCode: String? = null
        val totalSize = when {
            resp.code == 206 -> {
                val range = parseContentRange(resp.header("Content-Range"))
                    ?: return failure("Invalid Content-Range: ${resp.header("Content-Range") ?: "(missing)"}")
                if (range.start != 0L || range.end != 0L) {
                    return failure("Unexpected Content-Range for bytes=0-0: ${resp.header("Content-Range")}")
                }
                range.totalSize
            }
            resp.isSuccessful -> {
                val cl = resp.header("Content-Length")?.toLongOrNull()
                    ?: resp.body?.contentLength()?.takeIf { it >= 0 }
                    ?: return failure("Missing Content-Length")
                // OPPO's downloadCheck gate returns HTTP 200 with a tiny JSON body
                // (~49 B) like {"body":null,"errMsg":"2306","responseCode":2306} when
                // it rejects a request. Peek the body so we can surface the real cause.
                if (cl in 1..MAX_REJECTION_BODY_BYTES) {
                    rejectionCode = decodeRejectionCode(resp)
                }
                cl
            }
            else -> {
                return FirmwareUrlProbeResult.Failure(
                    detail = "HTTP ${resp.code}: ${resp.message}",
                    retryable = resp.code == 403 ||
                        resp.code == 410 ||
                        resp.code == 408 ||
                        resp.code == 429 ||
                        resp.code in 500..599,
                    httpCode = resp.code,
                )
            }
        }

        validateFirmwareSize(totalSize, expectedSize)?.let { problem ->
            return FirmwareUrlProbeResult.Failure(
                detail = rejectionCode?.let { "Anti-leech rejection (responseCode: $it)" } ?: problem,
                retryable = true,
                observedSize = totalSize,
                rejectionCode = rejectionCode,
            )
        }

        val md5 = resp.header("x-amz-meta-filemd5")?.trim()?.takeIf { it.isNotEmpty() }
        if (!expectedMd5.isNullOrBlank() && md5 != null && !md5.equals(expectedMd5.trim(), ignoreCase = true)) {
            return FirmwareUrlProbeResult.Failure("MD5 mismatch between catalog and CDN", retryable = false)
        }
        return FirmwareUrlProbeResult.Success(
            totalSize = totalSize,
            acceptsRanges = resp.code == 206,
            md5 = md5,
            resolvedUrl = resp.request.url.toString(),
        )
    }

    private fun decodeRejectionCode(resp: Response): String? = runCatching {
        val text = resp.peekBody(MAX_REJECTION_BODY_BYTES).string()
        REJECTION_CODE_RE.find(text)?.groupValues?.get(1)
    }.getOrNull()

    private fun failure(detail: String): FirmwareUrlProbeResult.Failure =
        FirmwareUrlProbeResult.Failure(detail = detail, retryable = true)

    private companion object {
        const val MAX_REJECTION_BODY_BYTES = 2048L
        val REJECTION_CODE_RE = Regex(""""responseCode"\s*:\s*"?(\d+)"?""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal sealed interface FirmwareUrlProbeResult {
    data class Success(
        val totalSize: Long,
        val acceptsRanges: Boolean,
        val md5: String?,
        val resolvedUrl: String,
    ) : FirmwareUrlProbeResult

    data class Failure(
        val detail: String,
        val retryable: Boolean,
        val observedSize: Long? = null,
        val httpCode: Int? = null,
        /** OPPO `downloadCheck` `responseCode` when the body looks like an anti-leech rejection. */
        val rejectionCode: String? = null,
    ) : FirmwareUrlProbeResult
}
