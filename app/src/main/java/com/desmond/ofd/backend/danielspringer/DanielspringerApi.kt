package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.VersionResolver
import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.http.BROWSER_USER_AGENT
import com.desmond.ofd.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One entry from `GET /api/ota.php`. */
@Serializable
data class DanielspringerRelease(
    val id: String = "",
    val device: String = "",
    /** Site region label: `CN` / `EU` / `GLO` / `IN` / `NA`. */
    val region: String = "",
    /** Canonical model code, matching `ro.product.name` — e.g. `PLK110`. */
    val model: String = "",
    /** Comparable display version, e.g. `PLK110_16.0.9.400(CN01)`. */
    val version: String = "",
    @SerialName("ota_version") val otaVersion: String = "",
    @SerialName("security_patch") val securityPatch: String? = null,
    /**
     * Unresolved source URL from the site's catalog — in practice an OPPO `/downloadCheck` gate.
     * The API does not resolve redirects or mint temporary links.
     */
    @SerialName("source_url") val sourceUrl: String = "",
    @SerialName("size") val sizeBytes: Long? = null,
    val md5: String? = null,
    @SerialName("is_latest") val isLatest: Boolean = false,
)

@Serializable
private data class DanielspringerApiResponse(
    val count: Int = 0,
    val releases: List<DanielspringerRelease> = emptyList(),
)

/**
 * Outcome of an API query.
 *
 * The distinction that matters is [Unusable] versus [Unreachable]. The site answers persistent
 * scraping by escalating from 429 to an IP-level block of the entire domain — which takes the
 * API down with it. So a connection failure is precisely the signature of "stay away from this
 * host", the opposite of a reason to fall back to scraping it. Only [Unusable] — the server
 * answered, it just had nothing we could use — makes the HTML fallback worth attempting.
 */
sealed interface DanielspringerApiResult {
    data class Found(val release: DanielspringerRelease) : DanielspringerApiResult
    data class Unusable(val detail: String) : DanielspringerApiResult
    data class Unreachable(val detail: String) : DanielspringerApiResult
}

/**
 * Read-only client for danielspringer's public OTA API.
 *
 * Preferred over scraping the HTML form: it is a plain cacheable GET keyed by the model code the
 * app already has, it needs no session or POST, and it is the access path the site documents for
 * apps and scripts. The scrape survives only as a fallback for the rare model the API misses.
 */
internal class DanielspringerApi(
    private val httpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class CachedResponse(val etag: String, val body: String)

    /** ETag cache, so repeated checks cost the server a 304 rather than a rebuild. */
    private val cache = ConcurrentHashMap<String, CachedResponse>()

    /**
     * Newest release for [model], preferring the entry whose region matches [region].
     *
     * Deliberately queried by model alone rather than model+region: the app infers region from
     * device properties and can get it wrong, and a mismatched region filter returns nothing at
     * all. Asking for the model and choosing afterwards degrades to "newest we know of" instead.
     */
    suspend fun latest(model: String, region: Region): DanielspringerApiResult {
        if (model.isBlank()) return DanielspringerApiResult.Unusable("no model code to look up")
        val path = "/api/ota.php?model=${enc(model)}&latest=1"
        val body = when (val fetched = fetch(path)) {
            is Fetched.Body -> fetched.text
            is Fetched.Failed -> return fetched.result
        }
        val parsed = runCatching { json.decodeFromString<DanielspringerApiResponse>(body) }.getOrNull()
            ?: return DanielspringerApiResult.Unusable("API response was not valid JSON")
        val releases = parsed.releases.filter {
            it.model.equals(model.trim(), ignoreCase = true) && it.version.isNotBlank() && it.sourceUrl.isNotBlank()
        }
        if (releases.isEmpty()) {
            return DanielspringerApiResult.Unusable("$model is not in the danielspringer catalog")
        }
        val matchingRegion = releases.filter { it.region.equals(region.toSiteRegion(), ignoreCase = true) }
        val preferred = (matchingRegion.ifEmpty { releases })
            .maxWith { a, b -> VersionResolver.compare(a.version, b.version) }
        return DanielspringerApiResult.Found(preferred)
    }

    private sealed interface Fetched {
        data class Body(val text: String) : Fetched
        data class Failed(val result: DanielspringerApiResult) : Fetched
    }

    private suspend fun fetch(path: String): Fetched = withContext(Dispatchers.IO) {
        val cached = cache[path]
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "application/json")
        cached?.let { builder.header("If-None-Match", it.etag) }
        try {
            httpClient.newCall(builder.build()).await().use { resp ->
                when {
                    // A 304 has no body. Returning that empty string would look like a parse
                    // failure, which would classify as Unusable and send us to the scraper — the
                    // one path that risks getting the whole domain blocked. Serve the cache.
                    resp.code == 304 -> cached
                        ?.let { Fetched.Body(it.body) }
                        ?: Fetched.Failed(DanielspringerApiResult.Unusable("304 with nothing cached"))

                    // Explicit back-off. Scraping now is what turns a soft limit into a hard ban.
                    resp.code in setOf(401, 403, 408, 429) || resp.code in 500..599 -> Fetched.Failed(
                        DanielspringerApiResult.Unreachable("site unavailable (HTTP ${resp.code})"),
                    )

                    resp.isSuccessful -> {
                        val text = resp.body?.string().orEmpty()
                        if (text.isBlank()) {
                            Fetched.Failed(DanielspringerApiResult.Unusable("API returned an empty body"))
                        } else {
                            val etag = resp.header("ETag")
                            if (etag != null) cache[path] = CachedResponse(etag, text) else cache.remove(path)
                            Fetched.Body(text)
                        }
                    }

                    else -> Fetched.Failed(
                        DanielspringerApiResult.Unusable("API returned HTTP ${resp.code}"),
                    )
                }
            }
        } catch (e: IOException) {
            // Connect refused / reset / DNS failure / timeout. On this host that is what an
            // IP-level block looks like, so it must not be read as "try the scraper instead".
            Fetched.Failed(
                DanielspringerApiResult.Unreachable(
                    e.message ?: e::class.simpleName ?: "could not reach the site",
                ),
            )
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_BASE_URL = "https://roms.danielspringer.at"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
