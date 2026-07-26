package com.desmond.ofd.download

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * [resolveGate] is applied to whatever [provider] returns, turning an OPPO `/downloadCheck`
 * gate URL into a directly fetchable one; it is identity for sources that are already final.
 */
internal class UrlHolder(
    private val provider: DownloadUrlProvider,
    private val resolveGate: suspend (String) -> String,
) {
    private val mutex = Mutex()
    @Volatile private var url: String = ""
    @Volatile private var generation: Int = 0

    /** The current URL paired with the generation it belongs to. */
    fun current(): Pair<String, Int> = url to generation

    /** Resolve for the first time. Propagates the failure, which carries the useful detail. */
    suspend fun resolveInitial(): String = mutex.withLock { resolveLocked() }

    /**
     * Re-resolve, unless another caller already did so since [seenGeneration] — in which case
     * their URL is returned untouched. Null when re-resolution was needed but failed.
     */
    suspend fun refresh(seenGeneration: Int): String? = mutex.withLock {
        if (generation != seenGeneration) return@withLock url
        runCatching { resolveLocked() }.getOrNull()
    }

    private suspend fun resolveLocked(): String {
        val resolved = resolveGate(provider())
        url = resolved
        generation += 1
        return resolved
    }
}
