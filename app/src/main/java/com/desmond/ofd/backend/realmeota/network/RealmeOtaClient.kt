package com.desmond.ofd.backend.realmeota.network

import com.desmond.ofd.http.await
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.data.OtaResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Orchestrates one OPPO OTA query: builds, encrypts, POSTs, decrypts, retries once on rejection. */
class RealmeOtaClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    suspend fun query(params: OtaRequestParams): OtaResult = withContext(Dispatchers.IO) {
        val first = queryOnce(params)
        if (first is OtaResult.HttpError && shouldRetryWithZeroOta(params)) {
            val retryParams = params.copy(otaVersion = makeZeroOtaVersion(params.otaVersion))
            return@withContext queryOnce(retryParams)
        }
        first
    }

    private suspend fun queryOnce(params: OtaRequestParams): OtaResult {
        val req = try {
            RealmeOtaRequest.build(params)
        } catch (t: Throwable) {
            return OtaResult.CryptoError(t)
        }

        val httpReq = Request.Builder()
            .url(req.url)
            .post(req.bodyJson.toRequestBody(JSON_MEDIA))
            .also { b -> req.headers.forEach { (k, v) -> b.header(k, v) } }
            .build()

        val response = try {
            httpClient.newCall(httpReq).await()
        } catch (e: IOException) {
            return OtaResult.NetworkError(e)
        }

        return response.use { r ->
            val body = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                // A network drop mid-body must not escape and crash the enclosing check.
                return@use OtaResult.NetworkError(e)
            }
            if (!r.isSuccessful) {
                return@use OtaResult.HttpError(r.code, "HTTP ${r.code}: ${r.message}")
            }
            val decoder = RealmeOtaDecoder(params.ruiVersion, params.reqVersion, req.storedAesKeyB64)
            decoder.envelopeError(body)?.let { err ->
                return@use OtaResult.HttpError(err.responseCode, err.errMsg)
            }
            try {
                val dto = decoder.decode(body)
                if (dto.checkFailReason != null) {
                    OtaResult.ContentError(dto.checkFailReason)
                } else {
                    OtaResult.Success(dto, body)
                }
            } catch (t: Throwable) {
                OtaResult.CryptoError(t)
            }
        }
    }

    private fun shouldRetryWithZeroOta(params: OtaRequestParams): Boolean =
        params.otaVersion.takeLast(ZERO_TAIL_LEN) != ZERO_TAIL

    /** Mirror of realme-ota's `args.ota_version[:-17] + '0001_000000000001'`. */
    private fun makeZeroOtaVersion(otaVersion: String): String =
        if (otaVersion.length >= ZERO_TAIL_LEN)
            otaVersion.dropLast(ZERO_TAIL_LEN) + ZERO_TAIL
        else
            otaVersion + "_$ZERO_TAIL"

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val ZERO_TAIL = "0001_000000000001"
        const val ZERO_TAIL_LEN = 17

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

sealed class OtaResult {
    data class Success(val response: OtaResponseDto, val rawJson: String) : OtaResult()
    data class HttpError(val code: Int, val errMsg: String?) : OtaResult()
    data class NetworkError(val cause: Throwable) : OtaResult()
    data class CryptoError(val cause: Throwable) : OtaResult()
    data class ContentError(val checkFailReason: String) : OtaResult()
}
