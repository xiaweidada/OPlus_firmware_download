package com.desmond.ofd.ui.screens

import android.net.Uri
import com.desmond.ofd.backend.mirror.MirrorClient
import com.desmond.ofd.diag.CheckDiagnostics
import com.desmond.ofd.diag.DeviceDiagnostics
import com.desmond.ofd.download.DownloadParams
import com.desmond.ofd.firmware.FirmwareSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeViewModelDownloadTest {
    @Test fun queues_foreground_work_before_contacting_the_mirror_and_keeps_the_selected_check() {
        val application = RuntimeEnvironment.getApplication()
        var networkCalls = 0
        val mirror = MirrorClient(
            application, baseUrl = "https://mirror.test", key = "fixture-key",
            userAgent = "fixture-agent", downloadEmail = "fixture@example.test",
            httpClient = OkHttpClient.Builder().addInterceptor {
                networkCalls++
                error("The UI must not perform the token exchange before starting foreground work")
            }.build(),
        )
        var queued: DownloadParams? = null
        val viewModel = HomeViewModel(application, mirrorClient = mirror, enqueueDownload = { _, params ->
            queued = params
            "queued-id"
        })
        val check = CheckDiagnostics(
            device = DeviceDiagnostics(model = "PLK110"), backends = emptyList(), winner = "Mirror",
        )
        val selected = BackendOutcome.Success(
            versionName = "PLK110_16.0.10.500(CN01)",
            source = FirmwareSource.MirrorProxy("OnePlus 15", "PLK110_11.A.72_0720_202607301131"),
            sizeBytes = 9146672362L, md5 = "2cbe1af4dece932fff62c81f5e6dbb68",
        )
        val result = viewModel.startDownload(Uri.parse("content://documents/selected"), selected, "firmware.zip", check)
        assertEquals(DownloadStart.Started("queued-id"), result)
        assertEquals(0, networkCalls)
        assertSame(check, queued!!.diagnostics!!.check)
        assertEquals(selected.md5, queued!!.expectedMd5)
    }

    @Test fun service_start_failure_is_reported_to_the_ui() {
        val viewModel = HomeViewModel(RuntimeEnvironment.getApplication(), enqueueDownload = { _, _ ->
            throw IllegalStateException("Service start refused")
        })
        val selected = BackendOutcome.Success(
            "PLK110_16.0.10.500(CN01)", FirmwareSource.Direct("https://example.org/file.zip"),
            sizeBytes = 9146672362L, md5 = null,
        )
        val result = viewModel.startDownload(Uri.parse("content://documents/selected"), selected, "firmware.zip")
        assertEquals(DownloadStart.Failed("Service start refused"), result)
    }
}
