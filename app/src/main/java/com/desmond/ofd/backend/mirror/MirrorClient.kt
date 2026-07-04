package com.desmond.ofd.backend.mirror

import android.content.Context
import android.util.Log
import com.desmond.ofd.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the secondary "latest" firmware source. Deliberately un-branded: nothing here
 * names the upstream in the UI. Configuration (base URL / signing key / user-agent) lives in
 * BuildConfig, sourced from git-ignored `local.properties`; blank values disable the source.
 *
 * Version listing is open (path-signed only). The proxy download link is token-gated, so it is
 * resolved lazily at download time and its short-lived token is refreshed per chunk via
 * [downloadAuthHeaders].
 */
class MirrorClient(
    context: Context,
    private val baseUrl: String = BuildConfig.MIRROR_BASE_URL.trimEnd('/'),
    private val key: String = BuildConfig.MIRROR_KEY,
    private val userAgent: String = BuildConfig.MIRROR_UA,
    /**
     * Email carried in the download-token request. The source only issues a token for an
     * *authorized* (donor) email — a random or blank one is refused (`403 无下载权限` / `422`), so
     * metadata still resolves but downloads don't. Sourced from a build secret; do NOT ship a
     * personal donor email in public releases (it would be extractable and get rate-limited/banned).
     */
    private val downloadEmail: String = BuildConfig.MIRROR_EMAIL.trim(),
) {
    val enabled: Boolean = baseUrl.isNotBlank() && key.isNotBlank() && userAgent.isNotBlank()

    private val crypto by lazy { MirrorCrypto(key) }
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val prefs = context.applicationContext.getSharedPreferences("ofd_mirror", Context.MODE_PRIVATE)

    /** Stable per-install id (an abuse key, not a hardware id), generated once. */
    val fingerprint: String by lazy {
        prefs.getString(PREF_FINGERPRINT, null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString(PREF_FINGERPRINT, it).apply()
            }
    }
    @Volatile private var deviceCache: List<MirrorDeviceModel>? = null
    private class TokenState(val token: String, val expiresAtMillis: Long)
    private val tokens = ConcurrentHashMap<String, TokenState>()

    /** Latest full-package metadata for [modelCode], or null if unsupported/unavailable. */
    suspend fun resolveLatest(modelCode: String): MirrorVersion? {
        if (!enabled) return null
        return runCatching {
            val deviceName = deviceNameFor(modelCode) ?: return null
            val fv = getDecoded("/api/coloros/fullVersions/${enc(deviceName)}", MirrorVersionDto.serializer())
                ?: return null
            if (fv.error != null || fv.romVersion.isBlank()) return null
            MirrorVersion(
                deviceName = deviceName,
                versionName = fv.romVersion,
                otaVersion = fv.otaVersion,
                sizeBytes = fv.sizeBytes.toLongOrNull() ?: -1L,
                md5 = fv.md5.ifBlank { null },
                securityPatch = fv.securityPatch.ifBlank { null },
            )
        }.getOrNull()
    }

    /**
     * Resolve the short-lived proxy download URL, at download time. Returns a typed result so
     * callers can surface *why* it failed (rejected token, empty response, network error, …)
     * instead of collapsing every failure to a bare null.
     */
    suspend fun resolveDownloadUrl(deviceName: String, otaVersion: String): MirrorResolution {
        if (!enabled) return MirrorResolution.Failed("mirror source is not configured")
        if (downloadEmail.isBlank()) {
            return MirrorResolution.Failed("mirror downloads aren't set up for this build (no authorized email)")
        }
        if (otaVersion.isBlank()) {
            return MirrorResolution.Failed("this firmware has no OTA build id, so the mirror can't resolve a download")
        }
        return runCatching {
            val token = acquireToken(deviceName, otaVersion)
                ?: return MirrorResolution.Failed("download-token request was rejected by the mirror")
            val headers = mapOf("X-Download-Token" to token, "X-Client-Fingerprint" to fingerprint)
            val links = getDecoded(
                "/api/coloros/download/${enc(deviceName)}/${enc(otaVersion)}",
                MirrorDownloadLinks.serializer(),
                headers,
            ) ?: return MirrorResolution.Failed("mirror download endpoint returned no usable response")
            val url = links.fullUrl.ifBlank { null }
                ?: return MirrorResolution.Failed(
                    links.message.ifBlank { "mirror response contained no full-package URL" },
                )
            MirrorResolution.Resolved(url)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "resolveDownloadUrl failed for $deviceName / $otaVersion", e)
            MirrorResolution.Failed(e.message ?: e::class.simpleName ?: "unknown mirror error")
        }
    }

    /**
     * Token + fingerprint headers for the proxy download, refreshed near expiry. Invoked per
     * chunk by the download engine; throws (rather than sending an empty token) when no token can
     * be obtained, so the failure surfaces as a visible download error.
     */
    suspend fun downloadAuthHeaders(deviceName: String, otaVersion: String): Map<String, String> {
        val token = acquireToken(deviceName, otaVersion)
            ?: throw IOException("mirror download token unavailable")
        return mapOf("X-Download-Token" to token, "X-Client-Fingerprint" to fingerprint)
    }

    /**
     * A usable download token for [deviceName]+[otaVersion]: the cached one while fresh, otherwise
     * a freshly issued one. If re-issue fails but a (near-)expired token is still cached, that is
     * returned as a best-effort fallback. Returns null only when no token can be produced at all.
     */
    private suspend fun acquireToken(deviceName: String, otaVersion: String): String? {
        val cacheKey = "$deviceName|$otaVersion"
        val now = System.currentTimeMillis()
        val cached = tokens[cacheKey]
        if (cached != null && now < cached.expiresAtMillis - REFRESH_MARGIN_MS) return cached.token
        val body = json.encodeToString(
            MirrorTokenRequest.serializer(),
            MirrorTokenRequest(email = downloadEmail, device = deviceName, otaVersion = otaVersion, packageType = "full"),
        )
        val resp = postDecoded(
            "/api/coloros/premium/download-token", body, MirrorTokenResponse.serializer(),
            mapOf("X-Client-Fingerprint" to fingerprint),
        )
        val fresh = resp?.token.orEmpty()
        if (fresh.isNotBlank()) {
            tokens[cacheKey] = TokenState(fresh, now + resp!!.expiresIn.coerceAtLeast(30) * 1000)
            return fresh
        }
        // Re-issue produced no token: reuse the last good one if we still have it.
        return cached?.token
    }

    // ---- internals ----

    private suspend fun deviceNameFor(modelCode: String): String? {
        val list = deviceCache
            ?: getDecodedList("/api/coloros/devices/", MirrorDeviceModel.serializer())?.also { deviceCache = it }
        val entry = list?.firstOrNull { it.deviceModel.equals(modelCode, ignoreCase = true) } ?: return null
        return normalizeName(entry.deviceName)
    }

    /** Chinese brand names → the English keys the endpoint expects (verified against the API). */
    private fun normalizeName(name: String): String =
        name.replace("一加", "OnePlus")
            .replace("真我", "Realme")
            .replace("+", " Plus")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> getDecoded(
        apiPath: String,
        serializer: DeserializationStrategy<T>,
        extra: Map<String, String> = emptyMap(),
    ): T? {
        val body = call(apiPath, "GET", null, extra) ?: return null
        return runCatching { json.decodeFromString(serializer, body) }.getOrNull()
    }

    private suspend fun <T> getDecodedList(apiPath: String, element: KSerializer<T>): List<T>? {
        val body = call(apiPath, "GET", null, emptyMap()) ?: return null
        return runCatching { json.decodeFromString(ListSerializer(element), body) }.getOrNull()
    }

    private suspend fun <T> postDecoded(
        apiPath: String,
        bodyJson: String,
        serializer: DeserializationStrategy<T>,
        extra: Map<String, String>,
    ): T? {
        val body = call(apiPath, "POST", bodyJson, extra) ?: return null
        return runCatching { json.decodeFromString(serializer, body) }.getOrNull()
    }

    /** Sign the path, send the body (encrypted only for `/subscriptions/`), execute, decrypt the response. */
    private suspend fun call(
        apiPath: String,
        method: String,
        bodyJson: String?,
        extra: Map<String, String>,
    ): String? = withContext(Dispatchers.IO) {
        val signedPath = crypto.signColorOsPath(apiPath, System.currentTimeMillis() / 1000)
        val builder = Request.Builder().url(baseUrl + signedPath).header("User-Agent", userAgent)
        extra.forEach { (k, v) -> builder.header(k, v) }
        if (method == "GET") {
            builder.get()
        } else {
            // Non-GET bodies MUST be AES-wrapped: the server answers a plain body with
            // 401 "该 API 需要加密访问" (this API requires encrypted access). Verified live.
            val enc = crypto.encryptBody(bodyJson.orEmpty())
            builder.header("X-Encrypted-Data", "true")
                .header("X-Encryption-Key", enc.keyB64)
                .header("X-Encryption-IV", enc.ivB64)
                .post(enc.body.toRequestBody(PLAIN_TEXT))
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "$method $apiPath -> HTTP ${resp.code}")
                return@withContext null
            }
            val raw = resp.body?.string().orEmpty()
            if (resp.header("x-encrypted-data") == "true") {
                val k = resp.header("x-encryption-key") ?: return@withContext null
                val iv = resp.header("x-encryption-iv") ?: return@withContext null
                crypto.decryptResponse(raw, k, iv)
            } else {
                raw
            }
        }
    }

    private companion object {
        const val TAG = "OFD-Mirror"
        const val PREF_FINGERPRINT = "fingerprint"
        const val REFRESH_MARGIN_MS = 15_000L
        val PLAIN_TEXT = "text/plain; charset=utf-8".toMediaType()
    }
}
