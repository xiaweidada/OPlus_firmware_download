package com.desmond.ofd.firmware

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

// Floor to distinguish a real firmware package from an anti-leech/error body (tens of bytes)
// or an HTML error page (a few KB). Kept well below any real full package (which run multiple
// GB) but high enough to reject junk. Previously 1 GiB, which risked rejecting legitimate
// smaller packages; the anti-leech `userId` fix now makes short rejection bodies rare anyway.
internal const val MIN_FULL_FIRMWARE_BYTES = 100L shl 20 // 100 MiB

internal fun validateFirmwareSize(resolvedSize: Long, expectedSize: Long): String? {
    if (resolvedSize in 0 until MIN_FULL_FIRMWARE_BYTES) {
        return "Suspiciously small full-package response: $resolvedSize bytes"
    }
    if (resolvedSize > 0 && expectedSize > 0 && resolvedSize != expectedSize) {
        return "Size mismatch: server=$resolvedSize bytes, expected=$expectedSize bytes"
    }
    return null
}

internal fun formatFirmwareBytes(bytes: Long): String = when {
    bytes < 0 -> "unknown"
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GiB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MiB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KiB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

// Smallest value accepted as an absolute expiry (2001-09-09). Some signing schemes put a
// *relative* lifetime (e.g. `3600`) in the same parameter name; rendering that as an absolute
// timestamp would show a 1970 date and make every link look permanently expired.
private const val MIN_PLAUSIBLE_EPOCH_SECONDS = 1_000_000_000L

// Pre-signed firmware URLs name their expiry differently per CDN: OPPO's CN edge
// (`*.allawnfs.com`) signs AWS-style with `Expires`, the EU/GL/IN edges (`*.allawnofs.com`)
// sign Alibaba-OSS-style with `x-oss-expires`, and the NA edge (`redirector.gvt1.com`)
// carries no expiry at all. Matched case-insensitively because `HttpUrl.queryParameter` is
// case-sensitive and the CDNs are not consistent about casing.
private val EXPIRY_PARAM_NAMES = setOf("expires", "x-oss-expires")

/** Epoch seconds at which a pre-signed URL stops working, or null when it carries no expiry. */
internal fun parseFirmwareUrlExpiresEpochSeconds(url: String): Long? = runCatching {
    val parsed = url.toHttpUrlOrNull() ?: return@runCatching null
    parsed.queryParameterNames
        .firstOrNull { it.lowercase(Locale.US) in EXPIRY_PARAM_NAMES }
        ?.let(parsed::queryParameter)
        ?.toLongOrNull()
        ?.takeIf { it >= MIN_PLAUSIBLE_EPOCH_SECONDS }
}.getOrNull()
