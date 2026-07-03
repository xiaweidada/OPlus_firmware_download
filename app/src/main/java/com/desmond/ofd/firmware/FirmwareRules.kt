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

internal fun parseFirmwareUrlExpiresEpochSeconds(url: String): Long? = runCatching {
    url.toHttpUrlOrNull()?.queryParameter("Expires")?.toLongOrNull()
}.getOrNull()
