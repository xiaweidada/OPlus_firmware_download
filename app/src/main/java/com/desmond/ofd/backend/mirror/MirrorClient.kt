package com.desmond.ofd.backend.mirror

import android.content.Context
import android.util.Log
import com.desmond.ofd.BuildConfig
import com.desmond.ofd.firmware.isFirmwareDownloadUrl
import com.desmond.ofd.firmware.validateFirmwareSize
import com.desmond.ofd.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the secondary "latest" firmware source. Deliberately un-branded: nothing here
 * names the upstream in the UI. Configuration (base URL / signing key / user-agent) lives in
 * BuildConfig, sourced from git-ignored `local.properties`; blank values disable the source.
 *
 * Version listing is open (path-signed only). Download links are token-gated and short-lived, so
 * they are minted lazily at download time — and the proxy's redirect is followed here rather than
 * downstream, so the downloader never has to carry a mirror credential.
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
    httpClient: OkHttpClient = defaultClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    val enabled: Boolean = baseUrl.isNotBlank() && key.isNotBlank() && userAgent.isNotBlank()

    private val crypto by lazy { MirrorCrypto(key) }
    private val json = Json { ignoreUnknownKeys = true }
    // Neither API requests nor proxy hops may forward credentials through an automatic redirect.
    private val http = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
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
    private val tokenMutex = Mutex()

    /**
     * Latest full-package metadata for [modelCode].
     *
     * Distinguishes the three ways this comes up empty — unconfigured build, model outside the
     * mirror's catalog, and mirror trouble — because collapsing them made "the source is down"
     * indistinguishable from "this build has no mirror", which is undiagnosable from a bug report.
     */
    suspend fun resolveLatest(modelCode: String): MirrorLookup {
        if (!enabled) return MirrorLookup.Unavailable(MirrorUnavailableReason.NOT_CONFIGURED)
        return runCatching {
            val deviceName = deviceNameFor(modelCode)
                ?: return MirrorLookup.Unavailable(MirrorUnavailableReason.MODEL_NOT_COVERED)
            val fv = getDecoded("/api/coloros/fullVersions/${enc(deviceName)}", MirrorVersionDto.serializer())
                ?: return MirrorLookup.Unavailable(MirrorUnavailableReason.UNAVAILABLE)
            // The endpoint answers HTTP 200 with an `error` field for an unknown device.
            if (fv.error != null) return MirrorLookup.Unavailable(MirrorUnavailableReason.MODEL_NOT_COVERED)
            val size = fv.sizeBytes.toLongOrNull() ?: -1L
            if (fv.romVersion.isBlank() || fv.otaVersion.isBlank() || validateFirmwareSize(size, -1L) != null) {
                return MirrorLookup.Unavailable(MirrorUnavailableReason.UNAVAILABLE)
            }
            MirrorLookup.Found(
                MirrorVersion(
                    deviceName = deviceName,
                    versionName = fv.romVersion,
                    otaVersion = fv.otaVersion,
                    sizeBytes = size,
                    md5 = fv.md5.trim().ifBlank { null },
                    securityPatch = fv.securityPatch.ifBlank { null },
                ),
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            MirrorLookup.Unavailable(MirrorUnavailableReason.UNAVAILABLE)
        }
    }

    /**
     * Resolve a ready-to-download URL, at download time. Returns a typed result so callers can
     * surface *why* it failed (rejected token, empty response, network error, …) instead of
     * collapsing every failure to a bare null.
     *
     * The proxy answers with a 302 to a pre-signed OPPO CDN URL. That hop is followed *here*,
     * with the mirror's own client, and only the CDN URL is handed back. Two consequences that
     * the download path depends on:
     *
     *  - No mirror credential ever reaches OPPO's CDN. OkHttp forwards custom headers across a
     *    cross-host redirect, and the download token is a JWT whose `sub` is the authorized
     *    email — following the redirect downstream would hand that to a third party.
     *  - The returned URL needs no headers at all and outlives the ~2 min token, so the
     *    downloader needs nothing mirror-specific. It does carry its own signature expiry,
     *    which is why callers must be able to ask for a fresh one (see [DownloadParams]).
     */
    suspend fun resolveDownloadUrl(deviceName: String, otaVersion: String): MirrorResolution {
        if (!enabled) return MirrorResolution.Failed(MirrorDownloadFailure.NOT_CONFIGURED)
        if (downloadEmail.isBlank()) {
            return MirrorResolution.Failed(MirrorDownloadFailure.NO_AUTHORIZED_EMAIL)
        }
        if (otaVersion.isBlank()) {
            return MirrorResolution.Failed(MirrorDownloadFailure.NO_OTA_VERSION)
        }
        return runCatching {
            repeat(2) { attempt ->
                val token = acquireToken(deviceName, otaVersion)
                    ?: return MirrorResolution.Failed(MirrorDownloadFailure.TOKEN_REJECTED)
                try {
                    val headers = mapOf("X-Download-Token" to token, "X-Client-Fingerprint" to fingerprint)
                    val links = getDecoded(
                        "/api/coloros/download/${enc(deviceName)}/${enc(otaVersion)}",
                        MirrorDownloadLinks.serializer(), headers,
                    ) ?: return MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK)
                    val proxyUrl = links.fullUrl.ifBlank { null }
                        ?: return MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK)
                    val pinned = followProxyRedirect(proxyUrl, token)
                        ?: return MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK)
                    return MirrorResolution.Resolved(pinned)
                } catch (e: MirrorHttpException) {
                    if (e.code !in setOf(401, 403)) throw e
                    tokens.computeIfPresent("$deviceName|$otaVersion") { _, state ->
                        state.takeUnless { it.token == token }
                    }
                    if (attempt == 1) return MirrorResolution.Failed(MirrorDownloadFailure.TOKEN_REJECTED)
                }
            }
            MirrorResolution.Failed(MirrorDownloadFailure.TOKEN_REJECTED)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            if (BuildConfig.DEBUG) Log.w(TAG, "resolveDownloadUrl failed (${e::class.simpleName})")
            MirrorResolution.Failed(MirrorDownloadFailure.NO_LINK)
        }
    }

    /**
     * GET the proxy URL *without* following redirects and return the absolute `Location`.
     *
     * The proxy validates the token, the fingerprint and the mirror User-Agent on this hop only
     * — a browser-ish UA here is refused with `403 {"error":"Access Denied"}`, which is why this
     * must use the mirror's own client rather than the download engine's.
     */
    private suspend fun followProxyRedirect(proxyUrl: String, token: String): String? =
        withContext(Dispatchers.IO) {
            // A direct firmware URL needs no mirror headers. Proxy URLs must stay at the
            // configured origin; the API response is not permission to disclose a donor token.
            if (isFirmwareDownloadUrl(proxyUrl)) return@withContext proxyUrl
            val origin = baseUrl.toHttpUrlOrNull() ?: return@withContext null
            val proxy = origin.resolve(proxyUrl) ?: return@withContext null
            if (proxy.scheme != origin.scheme || proxy.host != origin.host || proxy.port != origin.port ||
                proxy.username.isNotEmpty() || proxy.password.isNotEmpty()
            ) return@withContext null
            val request = Request.Builder()
                .url(proxy)
                .header("User-Agent", userAgent)
                .header("X-Download-Token", token)
                .header("X-Client-Fingerprint", fingerprint)
                .header("Accept", "*/*")
                .build()
            http.newCall(request).await().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw MirrorHttpException(resp.code)
                if (resp.code !in setOf(301, 302, 303, 307, 308)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "proxy redirect hop -> HTTP ${resp.code}")
                    return@withContext null
                }
                resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() }
                    ?.takeIf(::isFirmwareDownloadUrl)
            }
        }

    /**
     * A usable download token for [deviceName]+[otaVersion]: the cached one while fresh, otherwise
     * a freshly issued one. Returns null only when no token can be produced at all.
     *
     * Issuance is single-flight. Tokens live ~2 minutes, so without the lock a download that
     * re-resolves from several chunks at once would fire one token POST per chunk — a burst the
     * mirror would be right to rate-limit.
     */
    private suspend fun acquireToken(deviceName: String, otaVersion: String): String? {
        val cacheKey = "$deviceName|$otaVersion"
        cachedFreshToken(cacheKey)?.let { return it }
        return tokenMutex.withLock {
            // Re-check: another caller may have issued one while we waited for the lock.
            cachedFreshToken(cacheKey) ?: issueToken(cacheKey, deviceName, otaVersion)
        }
    }

    private fun cachedFreshToken(cacheKey: String): String? {
        val cached = tokens[cacheKey] ?: return null
        return cached.token.takeIf { now() < cached.expiresAtMillis - REFRESH_MARGIN_MS }
    }

    /** Issue a new token. Refusal must never resurrect an expired cached credential. */
    private suspend fun issueToken(cacheKey: String, deviceName: String, otaVersion: String): String? {
        val now = now()
        val body = json.encodeToString(
            MirrorTokenRequest.serializer(),
            MirrorTokenRequest(email = downloadEmail, device = deviceName, otaVersion = otaVersion, packageType = "full"),
        )
        val resp = try {
            postDecoded(
                "/api/coloros/premium/download-token", body, MirrorTokenResponse.serializer(),
                mapOf("X-Client-Fingerprint" to fingerprint),
            )
        } catch (_: MirrorHttpException) {
            null
        }
        val issued = resp?.token.orEmpty()
        val lifetime = resp?.expiresIn ?: 0L
        if (issued.isNotBlank() && lifetime > 0 && lifetime <= (Long.MAX_VALUE - now) / 1000L) {
            tokens[cacheKey] = TokenState(issued, now + lifetime * 1000L)
            return issued
        }
        tokens.remove(cacheKey)
        return null
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
        return json.decodeFromString(serializer, body)
    }

    private suspend fun <T> getDecodedList(apiPath: String, element: KSerializer<T>): List<T>? {
        val body = call(apiPath, "GET", null, emptyMap()) ?: return null
        return json.decodeFromString(ListSerializer(element), body)
    }

    private suspend fun <T> postDecoded(
        apiPath: String,
        bodyJson: String,
        serializer: DeserializationStrategy<T>,
        extra: Map<String, String>,
    ): T? {
        val body = call(apiPath, "POST", bodyJson, extra) ?: return null
        return json.decodeFromString(serializer, body)
    }

    /** Sign the path, encrypt POST bodies, execute, and decrypt the response. */
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
        http.newCall(builder.build()).await().use { resp ->
            if (!resp.isSuccessful) {
                if (BuildConfig.DEBUG) Log.w(TAG, "$method $apiPath -> HTTP ${resp.code}")
                throw MirrorHttpException(resp.code)
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

    private class MirrorHttpException(val code: Int) : IOException("Mirror request returned HTTP $code")

    private companion object {
        const val TAG = "OFD-Mirror"
        const val PREF_FINGERPRINT = "fingerprint"
        const val REFRESH_MARGIN_MS = 15_000L
        val PLAIN_TEXT = "text/plain; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Whether this build has a mirror configured at all.
 *
 * Shared so the UI and the backend agree: an open-source clone builds with blank secrets, and a
 * backend row that can only ever fail reads as a bug rather than as an absent optional source.
 */
object MirrorConfig {
    val isConfigured: Boolean =
        BuildConfig.MIRROR_BASE_URL.isNotBlank() &&
            BuildConfig.MIRROR_KEY.isNotBlank() &&
            BuildConfig.MIRROR_UA.isNotBlank()
}
