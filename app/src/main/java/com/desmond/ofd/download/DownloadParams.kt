package com.desmond.ofd.download

import android.net.Uri
import com.desmond.ofd.diag.DownloadDiagnostics

/**
 * Produces a ready-to-download URL, and produces a *fresh* one each time it is called.
 *
 * Firmware links are short-lived — pre-signed, valid for a handful of minutes — while a full
 * package runs to several GB and takes far longer to fetch. Every backend does hold a durable
 * handle that can mint a new link on demand, though: an OPPO `/downloadCheck` gate URL for
 * realme-ota and danielspringer, a device + build pair for the mirror. Modelling the source as
 * "something re-resolvable" rather than as a fixed URL is what lets a download outlive its own
 * signature instead of failing every chunk the moment it expires.
 *
 * Implementations stay in-process (never serialized) and may perform network I/O.
 */
typealias DownloadUrlProvider = suspend () -> String

/** Inputs for one download. Created when the user picks a save location via SAF. */
data class DownloadParams(
    val urlProvider: DownloadUrlProvider,
    val targetUri: Uri,
    val displayName: String,
    val expectedSize: Long,
    val expectedMd5: String?,
    /**
     * Context for the failure report. Carried here because the Downloads screen is a sibling
     * navigation destination that reads the coordinator directly and cannot reach the ViewModel
     * that ran the check — so this is the only route by which check-time context reaches a
     * failure card. Immutable plain values only; this is retained for the lifetime of the card.
     */
    val diagnostics: DownloadDiagnostics? = null,
)
