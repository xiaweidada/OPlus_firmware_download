package com.desmond.ofd.backend.danielspringer

import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapeBreakerTest {

    /** In-memory stand-in for SharedPreferences; survives rebuilding the breaker, as prefs do. */
    private class FakeStore : BreakerStore {
        val longs = mutableMapOf<String, Long>()
        val ints = mutableMapOf<String, Int>()
        override fun getLong(key: String, fallback: Long) = longs[key] ?: fallback
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun getInt(key: String, fallback: Int) = ints[key] ?: fallback
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun remove(keys: List<String>) {
            keys.forEach { longs.remove(it); ints.remove(it) }
        }
    }

    private val hour = 60 * 60 * 1000L

    @Test fun scraping_is_allowed_from_a_clean_slate() {
        assertTrue(ScrapeBreaker(FakeStore()) { 0L }.mayScrape("PLK110"))
    }

    @Test fun a_scraped_model_goes_on_cooldown_but_others_do_not() {
        val store = FakeStore()
        var now = 1_000_000L
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteScrapeAttempted("PLK110")
        assertTrue("same model is on cooldown", !breaker.mayScrape("PLK110"))
        assertTrue("a different model is unaffected", breaker.mayScrape("CPH2747"))
        now += 11 * 60 * 1000L
        assertTrue("cooldown expires", breaker.mayScrape("PLK110"))
    }

    @Test fun host_trouble_suppresses_every_model() {
        val store = FakeStore()
        var now = 1_000_000L
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteHostTrouble()
        assertTrue(!breaker.mayScrape("PLK110"))
        assertTrue("the block is per-host, so no model is exempt", !breaker.mayScrape("CPH2747"))
    }

    @Test fun repeated_trouble_backs_off_further_each_time() {
        val store = FakeStore()
        var now = 1_000_000L
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteHostTrouble()
        now += 31 * 60 * 1000L
        assertTrue("first backoff has passed", breaker.mayScrape("PLK110"))

        breaker.noteHostTrouble()
        now += 31 * 60 * 1000L
        assertTrue("second backoff is longer than the first", !breaker.mayScrape("PLK110"))
    }

    @Test fun a_healthy_host_clears_accumulated_suspicion() {
        val store = FakeStore()
        var now = 1_000_000L
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteHostTrouble()
        breaker.noteHostTrouble()
        breaker.noteHostHealthy()
        assertTrue(breaker.mayScrape("PLK110"))
    }

    @Test fun the_deadline_survives_rebuilding_the_breaker() {
        // The block outlives the process, and force-closing the app is the natural reaction to a
        // failure — an in-memory guard would reset straight back into the blocked behaviour.
        val store = FakeStore()
        var now = 1_000_000L
        ScrapeBreaker(store) { now }.noteHostTrouble()
        assertTrue(!ScrapeBreaker(store) { now }.mayScrape("PLK110"))
    }

    @Test fun a_clock_moved_backwards_does_not_latch_the_breaker_open() {
        val store = FakeStore()
        var now = 10 * hour
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteHostTrouble()
        now = 1L // user set the clock back years
        assertTrue("a meaningless deadline is treated as expired", breaker.mayScrape("PLK110"))
    }

    @Test fun a_clock_that_jumped_forward_cannot_lock_scraping_out_for_years() {
        val store = FakeStore()
        var now = 10_000 * hour
        val breaker = ScrapeBreaker(store) { now }
        breaker.noteHostTrouble()
        // Deadline was written against a far-future clock; correcting it must not leave a
        // multi-year suppression behind.
        now = hour
        assertTrue(breaker.mayScrape("PLK110"))
    }
}
