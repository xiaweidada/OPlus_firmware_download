package com.desmond.ofd.backend.danielspringer

import android.content.Context
import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.firmware.FirmwareSource
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.firmware.firmwareSourceFor
import com.desmond.ofd.firmware.parseFirmwareUrlExpiresEpochSeconds
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What the danielspringer backend found, or why it found nothing. */
sealed interface DanielspringerLookup {
    data class Found(
        val versionName: String,
        val source: FirmwareSource,
        val displayUrl: String?,
        val sizeBytes: Long,
        val md5: String?,
        val securityPatch: String?,
        val realOtaVersion: String?,
        val expiresAtEpochSeconds: Long?,
        /** False when the HTML fallback produced this, which is worth knowing in a bug report. */
        val viaApi: Boolean,
    ) : DanielspringerLookup

    data class Failed(val detail: String) : DanielspringerLookup
}

/**
 * The danielspringer backend: JSON API first, HTML scrape as a guarded fallback.
 *
 * The fallback is deliberately hard to reach. Scraping is what the site rate-limits, and its
 * rate limiting escalates to an IP-level block of the whole domain — which also kills the API,
 * so an eager fallback would destroy the good path. It therefore runs only when the server
 * answered and simply had nothing usable, never when the server could not be reached, and only
 * when [ScrapeBreaker] says the host and this model are both off cooldown.
 */
class DanielspringerSource internal constructor(
    private val api: DanielspringerApi = DanielspringerApi(),
    private val scraper: DanielspringerClient = DanielspringerClient(),
    private val breaker: ScrapeBreaker,
    private val probe: FirmwareUrlProbe = FirmwareUrlProbe(),
) {
    /** Production entry point; the seam above exists for tests. */
    constructor(context: Context) : this(breaker = ScrapeBreaker(context))

    @Volatile private var catalogCache: DanielspringerCatalog? = null

    suspend fun latest(model: String, region: Region): DanielspringerLookup =
        when (val result = api.latest(model, region)) {
            is DanielspringerApiResult.Found -> {
                breaker.noteHostHealthy()
                resolveApiRelease(result.release)
            }
            // The host is unreachable or telling us to back off. Scraping now is what turns a
            // soft limit into a hard block, so this path deliberately gives up instead.
            is DanielspringerApiResult.Unreachable -> {
                breaker.noteHostTrouble()
                DanielspringerLookup.Failed(result.detail)
            }
            is DanielspringerApiResult.Unusable -> scrapeFallback(model, region, result.detail)
        }

    /**
     * Resolve the catalog URL and check liveness and identity. Size and MD5 are optional in
     * the API; retain its checksum even on CDN edges that don't expose a checksum header.
     */
    private suspend fun resolveApiRelease(release: DanielspringerRelease): DanielspringerLookup =
        when (val probed = probe.probe(
            release.sourceUrl,
            expectedSize = release.sizeBytes?.takeIf { it > 0 } ?: -1L,
            expectedMd5 = release.md5,
        )) {
            is FirmwareUrlProbeResult.Success -> DanielspringerLookup.Found(
                versionName = release.version,
                source = firmwareSourceFor(release.sourceUrl),
                displayUrl = probed.resolvedUrl,
                sizeBytes = probed.totalSize,
                md5 = release.md5?.trim()?.takeIf { it.isNotBlank() } ?: probed.md5,
                securityPatch = release.securityPatch?.takeIf { it.isNotBlank() },
                realOtaVersion = release.otaVersion.takeIf { it.isNotBlank() },
                expiresAtEpochSeconds = parseFirmwareUrlExpiresEpochSeconds(probed.resolvedUrl),
                viaApi = true,
            )
            is FirmwareUrlProbeResult.Failure ->
                DanielspringerLookup.Failed("download link could not be verified: ${probed.detail}")
        }

    private suspend fun scrapeFallback(
        model: String,
        region: Region,
        apiDetail: String,
    ): DanielspringerLookup {
        if (!breaker.mayScrape(model)) return DanielspringerLookup.Failed(apiDetail)
        breaker.noteScrapeAttempted(model)
        return runCatching {
            // An empty catalog means the page changed shape; don't cache that, or one bad fetch
            // would make every later check claim the model is unknown.
            val catalog = catalogCache
                ?: scraper.fetchCatalog().also { if (it.modelCount > 0) catalogCache = it }
            if (catalog.modelCount == 0) return DanielspringerLookup.Failed(apiDetail)
            val res = scraper.fetchLatestUrlForModel(catalog, model = model, region = region)
                ?: return DanielspringerLookup.Failed(apiDetail)
            DanielspringerLookup.Found(
                // displayName is the comparable dotted-numeric form VersionResolver expects.
                versionName = res.displayName,
                // Preserve a gate when supplied; a terminal scraped link cannot be renewed
                // without another visit to the site.
                source = res.source,
                displayUrl = res.downloadUrl,
                sizeBytes = res.sizeBytes,
                md5 = res.md5,
                securityPatch = res.securityPatch,
                realOtaVersion = res.realOtaVersion,
                expiresAtEpochSeconds = res.expiresAtEpochSeconds.takeIf { it > 0 },
                viaApi = false,
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            // A scrape that failed at the transport level carries the same warning as an
            // unreachable API: stop touching this host for a while.
            if (e is IOException) breaker.noteHostTrouble()
            DanielspringerLookup.Failed(e.message ?: apiDetail)
        }
    }
}
