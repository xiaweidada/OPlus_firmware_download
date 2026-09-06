package com.desmond.ofd.backend

import com.desmond.ofd.backend.danielspringer.DanielspringerLookup
import com.desmond.ofd.backend.danielspringer.DanielspringerSource
import com.desmond.ofd.backend.mirror.MirrorClient
import com.desmond.ofd.backend.mirror.MirrorLookup
import com.desmond.ofd.backend.mirror.MirrorResolution
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.backend.realmeota.network.OtaResult
import com.desmond.ofd.backend.realmeota.network.RealmeOtaClient
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelection
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelector
import com.desmond.ofd.firmware.FirmwareSource
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.http.FIRMWARE_ID_HEADER
import com.desmond.ofd.http.FIRMWARE_ID_VALUE
import com.desmond.ofd.http.FIRMWARE_USER_AGENT
import com.desmond.ofd.http.await
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Explicitly opt in; normal unit tests and release builds never depend on upstream uptime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveBackendSmokeTest {
    @Test fun danielspringer_plk110_gate_and_download_ranges() = runBlocking {
        assumeTrue(System.getenv("OFD_LIVE_SMOKE") == "1")
        val result = DanielspringerSource(RuntimeEnvironment.getApplication()).latest("PLK110", Region.CN)
        assertTrue("danielspringer lookup failed", result is DanielspringerLookup.Found)
        result as DanielspringerLookup.Found
        assertTrue(result.source is FirmwareSource.Gate)
        verifyRanges((result.source as FirmwareSource.Gate).gateUrl, result.sizeBytes, result.md5)
        println("danielspringer: ${result.versionName}; ${result.sizeBytes} bytes; md5=${result.md5}; ranges OK")
    }

    @Test fun mirror_plk110_token_proxy_and_download_ranges() = runBlocking {
        assumeTrue(System.getenv("OFD_LIVE_SMOKE") == "1")
        val mirror = MirrorClient(RuntimeEnvironment.getApplication())
        assertTrue("Supply mirror configuration for this opt-in smoke test", mirror.enabled)
        val metadata = mirror.resolveLatest("PLK110")
        assertTrue("Mirror metadata lookup failed", metadata is MirrorLookup.Found)
        val version = (metadata as MirrorLookup.Found).version
        val resolved = mirror.resolveDownloadUrl(version.deviceName, version.otaVersion)
        assertTrue("Mirror token/proxy resolution failed", resolved is MirrorResolution.Resolved)
        verifyRanges((resolved as MirrorResolution.Resolved).url, version.sizeBytes, version.md5)
        println("Mirror: ${version.versionName}; ${version.sizeBytes} bytes; md5=${version.md5}; ranges OK")
    }

    @Test fun realme_ota_plk110_request_decryption_and_download_ranges() = runBlocking {
        assumeTrue(System.getenv("OFD_LIVE_SMOKE") == "1")
        val result = RealmeOtaClient().query(OtaRequestParams(
            model = "PLK110", otaVersion = "PLK110_11.A.00_0001_100000000000",
            ruiVersion = 7, nvIdentifier = "10010111", region = Region.CN,
        ))
        assertTrue("realme-ota query failed", result is OtaResult.Success)
        val selected = RealmeOtaDownloadSelector.select((result as OtaResult.Success).response)
        assertTrue(selected is RealmeOtaDownloadSelection.Success)
        selected as RealmeOtaDownloadSelection.Success
        verifyRanges(selected.downloadUrls.first(), selected.sizeBytes, selected.md5)
        println("realme-ota: ${selected.versionName}; ${selected.sizeBytes} bytes; md5=${selected.md5}; ranges OK")
    }

    private suspend fun verifyRanges(raw: String, size: Long, md5: String?) {
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val result = FirmwareUrlProbe(http).probe(raw, size, expectedMd5 = md5)
        assertTrue("CDN probe failed", result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(size, result.totalSize)
        for (start in listOf(0L, size / 2, size - SAMPLE_BYTES)) {
            val end = start + SAMPLE_BYTES - 1
            val request = Request.Builder().url(result.resolvedUrl)
                .header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
                .header(FIRMWARE_ID_HEADER, FIRMWARE_ID_VALUE).header("User-Agent", FIRMWARE_USER_AGENT)
                .build()
            http.newCall(request).await().use { response ->
                assertEquals("range request", 206, response.code)
                assertEquals("bytes $start-$end/$size", response.header("Content-Range"))
                val bytes = response.body!!.bytes()
                assertEquals(SAMPLE_BYTES, bytes.size.toLong())
                if (start == 0L) assertTrue("ZIP signature", bytes.take(4) == listOf<Byte>(0x50, 0x4b, 0x03, 0x04))
            }
        }
    }

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024L
    }
}
