package com.desmond.ofd.download

import android.net.Uri
import com.desmond.ofd.firmware.MIN_FULL_FIRMWARE_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadEngineIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Test fun downloads_the_full_file_from_the_verified_backup_after_manual_cdn_404() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>()
        val engine = engine { request ->
            requests += request
            if (request.url.host == "gauss-compota-c-cn.allawnfs.com") errorResponse(request, 404)
            else rangeResponse(request)
        }
        val target = temporary.newFile()
        var finalBytes = 0L
        val outcome = engine.download(
            "backup", { MANUAL_URL }, resolver, Uri.fromFile(target), SIZE,
            onProgress = { bytes, _, _ -> finalBytes = bytes },
        )
        assertEquals(DownloadEngine.DownloadOutcome.Success(SIZE), outcome)
        assertEquals(SIZE, target.length())
        assertEquals(SIZE, finalBytes)
        assertTrue(requests.filter { it.header("Range") != "bytes=0-0" }.all {
            it.url.host == "gauss-compotaauto-c-cn.allawnfs.com"
        })
        assertEquals(ZERO_FILE_MD5, engine.computeMd5(resolver, Uri.fromFile(target)))
    }

    @Test fun reports_probe_404_without_starting_a_blind_full_download() = runBlocking {
        val requests = AtomicInteger()
        val engine = engine { request -> requests.incrementAndGet(); errorResponse(request, 404) }
        val target = temporary.newFile().apply { writeText("existing content") }
        val outcome = engine.download(
            "missing", { "https://example.org/file.zip" }, resolver, Uri.fromFile(target), SIZE,
            onProgress = { _, _, _ -> },
        )
        assertTrue(outcome is DownloadEngine.DownloadOutcome.HttpError)
        assertEquals(404, (outcome as DownloadEngine.DownloadOutcome.HttpError).code)
        assertEquals("only the probe, no blind full GET", 1, requests.get())
        assertEquals("existing content", target.readText())
    }

    @Test fun refreshes_a_404_during_parallel_download_without_restarting_the_file() = runBlocking {
        val resolutions = AtomicInteger()
        val engine = engine { request ->
            if (request.url.encodedPath == "/old.zip" && request.header("Range") != "bytes=0-0") {
                errorResponse(request, 404)
            } else rangeResponse(request)
        }
        val target = temporary.newFile()
        val outcome = engine.download(
            "refresh", {
                if (resolutions.incrementAndGet() == 1) "https://example.org/old.zip"
                else "https://example.org/fresh.zip"
            }, resolver, Uri.fromFile(target), SIZE,
            onProgress = { _, _, _ -> },
        )
        assertEquals(DownloadEngine.DownloadOutcome.Success(SIZE), outcome)
        assertEquals("concurrent 404s share a refresh", 2, resolutions.get())
        assertEquals(ZERO_FILE_MD5, engine.computeMd5(resolver, Uri.fromFile(target)))
    }

    @Test fun replacing_a_larger_target_truncates_the_old_trailing_bytes() = runBlocking {
        val engine = engine { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", SIZE.toString()).body(zeroBody(SIZE)).build()
        }
        val target = temporary.newFile()
        RandomAccessFile(target, "rw").use { it.setLength(SIZE + 64) }
        val outcome = engine.download(
            "replace", { "https://example.org/file.zip" }, resolver, Uri.fromFile(target), SIZE,
            onProgress = { _, _, _ -> },
        )
        assertEquals(DownloadEngine.DownloadOutcome.Success(SIZE), outcome)
        assertEquals(SIZE, target.length())
        assertEquals(ZERO_FILE_MD5, engine.computeMd5(resolver, Uri.fromFile(target)))
    }

    private fun engine(respond: (Request) -> Response) = DownloadEngine(
        OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request()) }.build(),
        Dispatchers.IO,
    )

    private fun rangeResponse(request: Request): Response {
        val range = request.header("Range")!!.removePrefix("bytes=").split('-')
        val start = range[0].toLong()
        val end = range[1].toLong()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
            .header("Content-Range", "bytes $start-$end/$SIZE")
            .header("x-amz-meta-filemd5", ZERO_FILE_MD5)
            .body(zeroBody(end - start + 1)).build()
    }

    private fun errorResponse(request: Request, code: Int): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Not Found")
            .body(zeroBody(0)).build()

    private fun zeroBody(length: Long): ResponseBody = object : ResponseBody() {
        private val stream = object : Source {
            private var remaining = length
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1L
                val count = minOf(byteCount, remaining, ZEROES.size.toLong()).toInt()
                sink.write(ZEROES, 0, count)
                remaining -= count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = length
        override fun source() = stream
    }

    private companion object {
        const val SIZE = MIN_FULL_FIRMWARE_BYTES
        const val ZERO_FILE_MD5 = "2f282b84e7e608d5852449ed940bfc51"
        val ZEROES = ByteArray(64 * 1024)
        const val MANUAL_URL = "https://gauss-compota-c-cn.allawnfs.com/remove-test/g-test/component-ota/file.zip?Signature=test"
    }
}
