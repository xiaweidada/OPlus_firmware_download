package com.desmond.ofd.download

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class UrlHolderTest {

    @Test fun initial_resolve_calls_provider_once() = runBlocking {
        val calls = AtomicInteger()
        val holder = holderOf(calls)
        assertEquals("url-1", holder.resolveInitial())
        assertEquals(1, calls.get())
        assertEquals("url-1" to 1, holder.current())
    }

    @Test fun gate_is_applied_to_whatever_the_provider_returns() = runBlocking {
        val holder = UrlHolder(provider = { "gate://x" }, resolveGate = { "resolved:$it" })
        assertEquals("resolved:gate://x", holder.resolveInitial())
    }

    @Test fun concurrent_refresh_of_one_generation_resolves_exactly_once() = runBlocking {
        // The case that matters: 64 chunks all see their link expire at the same instant.
        // Without single-flight this would be 64 re-resolutions, and for the mirror that means
        // 64 token requests in a burst.
        val calls = AtomicInteger()
        val holder = holderOf(calls, delayMs = 20)
        holder.resolveInitial()
        val (_, generation) = holder.current()

        val results = coroutineScope {
            (1..64).map { async { holder.refresh(generation) } }.awaitAll()
        }

        assertEquals("one initial + exactly one refresh", 2, calls.get())
        assertTrue("every chunk got the same refreshed URL", results.all { it == "url-2" })
        assertEquals("url-2" to 2, holder.current())
    }

    @Test fun refresh_of_a_stale_generation_does_not_resolve_again() = runBlocking {
        val calls = AtomicInteger()
        val holder = holderOf(calls)
        holder.resolveInitial()
        val stale = 0 // the generation before the initial resolve

        assertEquals("url-1", holder.refresh(stale))
        assertEquals("no extra resolve for a generation already superseded", 1, calls.get())
    }

    @Test fun refresh_returns_null_when_resolution_fails() = runBlocking {
        var fail = false
        val holder = UrlHolder(
            provider = { if (fail) throw IOException("gate down") else "url" },
            resolveGate = { it },
        )
        holder.resolveInitial()
        fail = true
        assertNull(holder.refresh(1))
        // The last good URL is kept so callers can still report something useful.
        assertEquals("url" to 1, holder.current())
    }

    @Test fun initial_resolve_propagates_failure_detail() = runBlocking {
        val holder = UrlHolder(provider = { throw IOException("anti-leech rejected") }, resolveGate = { it })
        val thrown = runCatching { holder.resolveInitial() }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals("anti-leech rejected", thrown!!.message)
    }

    private fun holderOf(calls: AtomicInteger, delayMs: Long = 0): UrlHolder = UrlHolder(
        provider = {
            if (delayMs > 0) delay(delayMs)
            "url-${calls.incrementAndGet()}"
        },
        resolveGate = { it },
    )
}
