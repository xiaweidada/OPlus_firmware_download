package com.desmond.ofd.download

import android.net.Uri
import com.desmond.ofd.backend.mirror.MirrorClient
import com.desmond.ofd.backend.mirror.MirrorLookup
import com.desmond.ofd.backend.mirror.MirrorResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads several GB only with explicit opt-in. The temporary package is always deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveDownloadTest {
    @Test fun downloads_and_hashes_the_complete_plk110_package() = runBlocking {
        assumeTrue(System.getenv("OFD_LIVE_DOWNLOAD") == "1")
        val context = RuntimeEnvironment.getApplication()
        val mirror = MirrorClient(context)
        val lookup = mirror.resolveLatest("PLK110")
        assertTrue("Mirror lookup failed", lookup is MirrorLookup.Found)
        val version = (lookup as MirrorLookup.Found).version
        assertTrue("A checksum is required for this live verification", !version.md5.isNullOrBlank())
        val http = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = DownloadEngine.MAX_CONCURRENT_CALLS
                maxRequestsPerHost = DownloadEngine.MAX_CONCURRENT_CALLS
            })
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val engine = DownloadEngine(http, Dispatchers.IO)
        val directory = File("build/live-download").apply { mkdirs() }
        val target = File.createTempFile("plk110-", ".zip", directory)
        try {
            var lastPercent = -10
            val outcome = engine.download(
                downloadId = "live-plk110", urlProvider = {
                    when (val resolved = mirror.resolveDownloadUrl(version.deviceName, version.otaVersion)) {
                        is MirrorResolution.Resolved -> resolved.url
                        is MirrorResolution.Failed -> throw IOException("Mirror resolution failed: ${resolved.reason}")
                    }
                }, contentResolver = context.contentResolver, targetUri = Uri.fromFile(target),
                expectedSize = version.sizeBytes, expectedMd5 = version.md5,
                onProgress = { bytes, total, speed ->
                    val percent = (bytes * 100 / total).toInt()
                    if (percent >= lastPercent + 10) {
                        println("PLK110 full download: $percent%; $bytes/$total bytes; ${speed / 1024} KiB/s")
                        lastPercent = percent
                    }
                },
            )
            assertEquals(DownloadEngine.DownloadOutcome.Success(version.sizeBytes), outcome)
            assertEquals(version.sizeBytes, target.length())
            val actualMd5 = engine.computeMd5(context.contentResolver, Uri.fromFile(target))
            assertEquals(version.md5, actualMd5)
            println("FULL DOWNLOAD VERIFIED: ${version.versionName}; ${target.length()} bytes; md5=$actualMd5")
        } finally {
            target.delete()
            http.connectionPool.evictAll()
        }
    }
}
