package com.desmond.ofd.download

import android.net.Uri

/** Inputs for one download. Created when the user picks a save location via SAF. */
data class DownloadParams(
    val url: String,
    val targetUri: Uri,
    val displayName: String,
    val expectedSize: Long,
    val expectedMd5: String?,
    /** Static extra request headers (e.g. a client fingerprint) sent on every chunk. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Optional provider of fresh auth headers, invoked per chunk/retry so a short-lived
     * download token can be refreshed mid-download. Stays in-process (never serialized).
     */
    val authProvider: (suspend () -> Map<String, String>)? = null,
)
