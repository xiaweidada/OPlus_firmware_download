package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.firmware.firmwareSourceFor
import com.desmond.ofd.http.BROWSER_USER_AGENT
import com.desmond.ofd.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Live URL fetch from danielspringer.at. Each call creates its own session
 * (fresh OkHttpClient + cookie jar) so concurrent calls don't trample each other.
 *
 * Two-step flow per session:
 *   1. GET `/index.php?view=ota` to seed `PHPSESSID`.
 *   2. POST `device=...&region=...&version_index=...` (auto-follows the 302 → result page),
 *      then parse the HTML. On the lazy layout, POST its session fields to `resolve_json`.
 *   3. Probe the resulting file, including the official CN CDN fallback when necessary.
 *
 * This is the *fallback* path. The site's JSON API is preferred — see [DanielspringerApi] — and
 * this exists for the rare model the API's catalog misses. Scraping is also what the site rate
 * limits, so callers must go through [DanielspringerSource], which guards it with a breaker.
 */
class DanielspringerClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = BASE_URL,
) {
    private val formUrl = "$baseUrl/index.php?view=ota"
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetch and parse the device → region → versions catalog. Cached by caller, not here. */
    suspend fun fetchCatalog(): DanielspringerCatalog = withContext(Dispatchers.IO) {
        val client = newScrapeClient()
        val req = Request.Builder()
            .url(formUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        val html = client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw DanielspringerHttpException(resp.code)
            resp.body?.string().orEmpty()
        }
        DanielspringerCatalog.parse(html)
    }

    /**
     * Resolve the latest (`version_index=0`) firmware URL for the given site labels.
     * Pass the labels straight from [DanielspringerCatalog.siteForModel].
     */
    suspend fun fetchLatestUrl(
        siteDevice: String,
        siteRegion: String,
        versionIndex: Int = 0,
    ): DanielspringerResult = withContext(Dispatchers.IO) {
        val client = newScrapeClient()

        // 1. seed PHPSESSID
        client.newCall(Request.Builder().url(formUrl).header("User-Agent", BROWSER_USER_AGENT).build())
            .await().use { response ->
                if (!response.isSuccessful) throw DanielspringerHttpException(response.code)
            }

        // 2. POST + auto-follow 302 → result page
        val body = FormBody.Builder()
            .add("device", siteDevice)
            .add("region", siteRegion)
            .add("version_index", versionIndex.toString())
            .build()
        val postReq = Request.Builder()
            .url(formUrl)
            .post(body)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", baseUrl)
            .header("Referer", formUrl)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        val resultHtml = client.newCall(postReq).await().use { resp ->
            if (!resp.isSuccessful) throw DanielspringerHttpException(resp.code)
            resp.body?.string().orEmpty()
        }

        val parsed = ResultParser.parseResultHtml(resultHtml, versionIndex)
        val downloadUrl = parsed.downloadUrl ?: parsed.lazyLink?.let { resolveLazyLink(client, it) }
            ?: throw IOException("Site returned no download URL — region/version may be empty for this device.")
        val displayName = parsed.displayName ?: "(unknown)"

        // 3. Range GET for accurate size + md5. AWS pre-signed URLs are sometimes signed
        //    only for GET; HEAD then returns 403 with a tiny error-page Content-Length
        //    that gets misread as the real file size. Range bytes=0-0 always yields 206
        //    Partial Content with `Content-Range: bytes 0-0/<TOTAL>`.
        val resolved = when (val probe = FirmwareUrlProbe(client).probe(downloadUrl, expectedMd5 = parsed.md5)) {
            is FirmwareUrlProbeResult.Success -> probe
            is FirmwareUrlProbeResult.Failure -> {
                throw IOException("Download URL probe failed: ${probe.detail}")
            }
        }

        DanielspringerResult(
            downloadUrl = resolved.resolvedUrl,
            sizeBytes = resolved.totalSize,
            md5 = parsed.md5 ?: resolved.md5,
            displayName = displayName,
            realOtaVersion = parsed.realOtaVersion,
            securityPatch = parsed.securityPatch,
            manualOnly = parsed.manualOnly,
            expiresAtEpochSeconds = ResultParser.parseExpiresEpochSeconds(resolved.resolvedUrl) ?: 0L,
            source = firmwareSourceFor(downloadUrl),
        )
    }

    private suspend fun resolveLazyLink(client: OkHttpClient, session: ResultParser.LazyLink): String {
        val body = FormBody.Builder().add("k", session.selectionKey).add("csrf", session.csrf).build()
        val request = Request.Builder().url("$formUrl&ota_action=resolve_json")
            .post(body).header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", baseUrl).header("Referer", formUrl).header("Accept", "application/json")
            .build()
        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw DanielspringerHttpException(response.code)
            val result = runCatching {
                json.decodeFromString<LazyResolution>(response.body?.string().orEmpty())
            }.getOrElse { throw IOException("Site returned an invalid link response", it) }
            if (!result.ok || !ResultParser.looksLikeFirmwareUrl(result.url)) {
                throw IOException("Site could not prepare a firmware download link")
            }
            result.url
        }
    }

    @Serializable
    private data class LazyResolution(val ok: Boolean = false, val url: String = "")

    /** Convenience wrapper: resolve via realme-ota's [Region] + a model code. */
    suspend fun fetchLatestUrlForModel(
        catalog: DanielspringerCatalog,
        model: String,
        region: Region,
        versionIndex: Int = 0,
    ): DanielspringerResult? {
        val (siteDevice, siteRegion) = catalog.siteForModel(model, region) ?: return null
        return fetchLatestUrl(siteDevice, siteRegion, versionIndex)
    }

    private fun newScrapeClient(): OkHttpClient = httpClient.newBuilder()
        .cookieJar(SimpleCookieJar())
        .build()

    companion object {
        const val BASE_URL = "https://roms.danielspringer.at"
        const val FORM_URL = "$BASE_URL/index.php?view=ota"

        // The form's own action gained a fragment when the page moved to JS comboboxes. Posting
        // to the declared action keeps us aligned with whatever the page expects.
        const val FORM_ACTION_URL = "$BASE_URL/index.php?view=ota#ota-downloader"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * A non-2xx answer from the site. An [IOException] on purpose: callers treat transport-level
 * trouble as "back off this host", and a 429 belongs in exactly that bucket.
 */
internal class DanielspringerHttpException(val code: Int) :
    IOException("danielspringer returned HTTP $code")

/** Per-call in-memory cookie jar — fresh state per [DanielspringerClient.fetchLatestUrl]. */
private class SimpleCookieJar : CookieJar {
    private val store = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store["${c.name}|${c.domain}|${c.path}"] = c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
}
