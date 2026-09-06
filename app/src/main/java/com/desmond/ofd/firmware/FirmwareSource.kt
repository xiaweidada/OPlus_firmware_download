package com.desmond.ofd.firmware

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Accepted firmware destinations for HTML results and credential-free mirror redirects. */
internal fun isFirmwareDownloadUrl(candidate: String?): Boolean {
    val url = candidate?.trim()?.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
    val allowed = listOf("allawnfs.com", "allawnofs.com", "allawntech.com", "allawnos.com", "googleapis.com", "gvt1.com")
    if (allowed.none { url.host == it || url.host.endsWith(".$it") }) return false
    val path = url.encodedPath.lowercase()
    return path.endsWith(".zip") || path.endsWith("/downloadcheck")
}

/**
 * Where a firmware package is fetched from — expressed as a *durable* handle rather than a URL.
 *
 * The links themselves are pre-signed and live only minutes, while a full package takes far
 * longer to download, so the thing worth remembering is whatever can mint a fresh link. Only
 * [Direct] cannot, which is why it is reserved for sources that hand over a terminal URL and
 * nothing else.
 */
sealed interface FirmwareSource {

    /**
     * An already-final URL with no way to renew it. Used by the danielspringer HTML fallback,
     * whose pre-signed link can only be replaced by scraping the site again.
     */
    data class Direct(val url: String) : FirmwareSource

    /**
     * An OPPO `/downloadCheck` gate URL. Durable: each hit (with the anti-leech header) answers
     * with a redirect to a newly signed CDN link, so this can be re-resolved indefinitely.
     * Used by realme-ota and by danielspringer's JSON API, which stores exactly such URLs.
     */
    data class Gate(val gateUrl: String) : FirmwareSource

    /**
     * The mirror's proxy, addressed by device name and build id. Re-resolving mints a fresh
     * download token and follows the proxy redirect to a new CDN link.
     */
    data class MirrorProxy(val deviceName: String, val otaVersion: String) : FirmwareSource
}

/**
 * Classify a URL. An OPPO `/downloadCheck` gate can be re-hit for a freshly signed link;
 * anything else is already terminal and cannot be renewed.
 */
internal fun firmwareSourceFor(url: String): FirmwareSource =
    if (FirmwareDownloadGate.isOplusDownloadGate(url)) {
        FirmwareSource.Gate(url)
    } else {
        FirmwareSource.Direct(url)
    }
