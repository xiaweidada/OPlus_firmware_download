package com.desmond.ofd.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    @Test fun cancelling_a_pending_request_closes_a_late_response() = runBlocking {
        val call = PendingCall()
        var closed = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
        pending.cancel()
        call.callback.onResponse(call, call.response { closed = true })
        pending.join()
        assertTrue(call.cancelled)
        assertTrue(closed)
    }

    @Test fun cancellation_after_headers_before_dispatch_closes_the_unconsumed_response() = runBlocking {
        val call = PendingCall()
        var closed = false
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }
        // The continuation is queued on this runBlocking event loop but has not consumed the
        // response. The old isActive-then-resume bridge leaked it when cancellation won here.
        call.callback.onResponse(call, call.response { closed = true })
        pending.cancel()
        pending.join()
        assertTrue(closed)
    }

    private class PendingCall : Call by OkHttpClient().newCall(
        Request.Builder().url("https://example.org/file.zip").build(),
    ) {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = Request.Builder().url("https://example.org/file.zip").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun timeout() = Timeout.NONE
        override fun execute(): Response = error("Only async calls are supported")
        override fun clone(): Call = PendingCall()

        fun response(onClose: () -> Unit): Response = Response.Builder().request(request())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(object : ResponseBody() {
                private val stream = object : Source {
                    override fun read(sink: Buffer, byteCount: Long) = -1L
                    override fun timeout() = Timeout.NONE
                    override fun close() = onClose()
                }.buffer()
                override fun contentType() = null
                override fun contentLength() = 0L
                override fun source() = stream
            }).build()
    }
}
