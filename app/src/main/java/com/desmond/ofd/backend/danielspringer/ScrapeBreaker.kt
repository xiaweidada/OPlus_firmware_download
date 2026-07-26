package com.desmond.ofd.backend.danielspringer

import android.content.Context

/** Minimal key-value store, so the breaker's logic is testable without an Android context. */
internal interface BreakerStore {
    fun getLong(key: String, fallback: Long): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, fallback: Int): Int
    fun putInt(key: String, value: Int)
    fun remove(keys: List<String>)
}

internal class PrefsBreakerStore(context: Context) : BreakerStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("ofd_danielspringer", Context.MODE_PRIVATE)

    override fun getLong(key: String, fallback: Long): Long = prefs.getLong(key, fallback)
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun getInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun remove(keys: List<String>) =
        prefs.edit().apply { keys.forEach(::remove) }.apply()
}

/**
 * Decides when scraping danielspringer must be left alone.
 *
 * The site answers persistent scraping by escalating from 429 to an IP-level block of the whole
 * domain — which takes its JSON API down with it, so over-scraping breaks the very path we
 * prefer. Two consequences shape this class:
 *
 *  - State is **persisted**. The block outlives the process, and the natural reaction to a
 *    failure is to force-close and reopen the app; an in-memory guard would reset straight back
 *    into the behaviour that caused the block.
 *  - The host-level deadline is the one that matters, because the block is per-host. The
 *    per-model cooldown only stops one user hammering one device.
 */
internal class ScrapeBreaker(
    private val store: BreakerStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(PrefsBreakerStore(context))

    /** True when a scrape for [model] is currently allowed. */
    fun mayScrape(model: String): Boolean =
        remaining(KEY_HOST_UNTIL) <= 0L && remaining(modelKey(model)) <= 0L

    /** Record that a scrape was just attempted, so the same model isn't retried immediately. */
    fun noteScrapeAttempted(model: String) = setDeadline(modelKey(model), MODEL_COOLDOWN_MS)

    /**
     * The host refused us or could not be reached. Back off, escalating on repeats — this is the
     * signal that we may already be blocked, and more requests deepen it.
     */
    fun noteHostTrouble() {
        val failures = (store.getInt(KEY_HOST_FAILURES, 0) + 1).coerceAtMost(MAX_FAILURES)
        store.putInt(KEY_HOST_FAILURES, failures)
        val backoff = (BASE_HOST_BACKOFF_MS shl (failures - 1)).coerceAtMost(MAX_HOST_BACKOFF_MS)
        setDeadline(KEY_HOST_UNTIL, backoff)
    }

    /** The host answered normally; forget the accumulated suspicion. */
    fun noteHostHealthy() {
        store.remove(listOf(KEY_HOST_FAILURES, KEY_HOST_UNTIL, KEY_HOST_UNTIL + STORED_SUFFIX))
    }

    private fun setDeadline(key: String, durationMs: Long) {
        val at = now()
        store.putLong(key, at + durationMs)
        store.putLong(key + STORED_SUFFIX, at)
    }

    /** Remaining milliseconds on a deadline, guarding against a clock that moved. */
    private fun remaining(key: String): Long {
        val until = store.getLong(key, 0L)
        if (until <= 0L) return 0L
        val storedAt = store.getLong(key + STORED_SUFFIX, 0L)
        val at = now()
        // Clock moved backwards since the deadline was written: it is meaningless now, and
        // honouring it would keep the breaker latched open indefinitely.
        if (at < storedAt) return 0L
        // Clamp, so a clock that had jumped far ahead when the deadline was written cannot lock
        // scraping out for years.
        return (until - at).coerceIn(0L, MAX_HOST_BACKOFF_MS)
    }

    private fun modelKey(model: String) = "model_until_${model.uppercase()}"

    private companion object {
        const val KEY_HOST_UNTIL = "host_until"
        const val KEY_HOST_FAILURES = "host_failures"
        const val STORED_SUFFIX = "_at"

        const val MODEL_COOLDOWN_MS = 10 * 60 * 1000L
        const val BASE_HOST_BACKOFF_MS = 30 * 60 * 1000L
        const val MAX_HOST_BACKOFF_MS = 6 * 60 * 60 * 1000L
        const val MAX_FAILURES = 4
    }
}
