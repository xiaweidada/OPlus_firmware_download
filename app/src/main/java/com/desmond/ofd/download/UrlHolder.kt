package com.desmond.ofd.download

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * A download's current URL, plus the ability to mint a fresh one.
 *
 * Pre-signed firmware URLs expire within minutes and a multi-GB download outlives them — when
 * that happens the CDN rejects every outstanding range at once. A chunk that sees such a
 * rejection asks for a refresh, quoting the generation it was using. Exactly one re-resolution
 * happens per generation however many chunks noticed at the same moment; the losers pick up
 * whatever URL the winner installed. Without that, 64 chunks noticing together would mean 64
 * re-resolutions, and for the mirror that means 64 token requests.
 *
 * [resolveGate] verifies whatever [provider] returns, including gate redirects and CDN fallback.
 */
internal class UrlHolder(
    private val provider: DownloadUrlProvider,
    initialUrl: String? = null,
    private val nowNanos: () -> Long = System::nanoTime,
    private val resolveGate: suspend (String) -> String,
) {
    private val mutex = Mutex()
    // Publish the URL and generation together; separate volatile reads could pair an old URL
    // with a new generation and cause a worker to refresh the wrong link.
    @Volatile private var snapshot = (initialUrl ?: "") to if (initialUrl == null) 0 else 1
    private var failedGeneration: Int? = null
    private var failedAtNanos = 0L

    /** The current URL paired with the generation it belongs to. */
    fun current(): Pair<String, Int> = snapshot

    /** Resolve for the first time. Propagates the failure, which carries the useful detail. */
    suspend fun resolveInitial(): String = mutex.withLock { resolveLocked() }

    /**
     * Re-resolve, unless another caller already did so since [seenGeneration] — in which case
     * their URL is returned untouched. Null when re-resolution was needed but failed.
     */
    suspend fun refresh(seenGeneration: Int): String? = mutex.withLock {
        val (url, generation) = snapshot
        if (generation != seenGeneration) return@withLock url
        // Share failed exchanges during the workers' backoff too, then allow a later retry
        // to recover without throwing away the file. Queued workers must not burst 64 requests.
        if (failedGeneration == seenGeneration && nowNanos() - failedAtNanos < REFRESH_BACKOFF_NANOS) {
            return@withLock null
        }
        try {
            resolveLocked()
        } catch (_: IOException) {
            failedGeneration = seenGeneration
            failedAtNanos = nowNanos()
            null
        }
    }

    private suspend fun resolveLocked(): String {
        val resolved = resolveGate(provider())
        snapshot = resolved to snapshot.second + 1
        failedGeneration = null
        return resolved
    }

    private companion object {
        const val REFRESH_BACKOFF_NANOS = 1_000_000_000L
    }
}
