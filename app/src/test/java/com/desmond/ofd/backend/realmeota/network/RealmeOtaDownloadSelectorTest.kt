package com.desmond.ofd.backend.realmeota.network

import com.desmond.ofd.backend.realmeota.data.Component
import com.desmond.ofd.backend.realmeota.data.ComponentPacket
import com.desmond.ofd.backend.realmeota.data.OtaResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmeOtaDownloadSelectorTest {

    @Test fun prefers_manual_url_and_trusts_ota_packet_metadata() {
        val response = otaResponse(
            manualUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=manual",
            autoUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            size = TWO_GIB.toString(),
            md5 = "abc123",
        )

        val selected = RealmeOtaDownloadSelector.select(response)

        assertTrue(selected is RealmeOtaDownloadSelection.Success)
        selected as RealmeOtaDownloadSelection.Success
        assertEquals(
            listOf(
                "https://component-ota-cn.allawntech.com/downloadCheck?tr=manual",
                "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            ),
            selected.downloadUrls,
        )
        assertEquals(TWO_GIB, selected.sizeBytes)
        assertEquals("abc123", selected.md5)
        assertEquals("PKJ110_16.0.5.702(CN01)", selected.versionName)
        assertEquals("PKJ110_11.C.65_1650_202604091920", selected.realOtaVersion)
    }

    @Test fun falls_back_to_auto_url_when_manual_url_is_missing() {
        val response = otaResponse(
            manualUrl = null,
            autoUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            size = TWO_GIB.toString(),
        )

        val selected = RealmeOtaDownloadSelector.select(response)

        assertTrue(selected is RealmeOtaDownloadSelection.Success)
        selected as RealmeOtaDownloadSelection.Success
        assertEquals(
            listOf("https://component-ota-cn.allawntech.com/downloadCheck?tr=auto"),
            selected.downloadUrls,
        )
    }

    @Test fun rejects_suspiciously_small_packet_size() {
        val response = otaResponse(
            manualUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=manual",
            autoUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            size = "49",
        )

        val selected = RealmeOtaDownloadSelector.select(response)

        assertTrue(selected is RealmeOtaDownloadSelection.Failure)
        selected as RealmeOtaDownloadSelection.Failure
        assertEquals(RealmeOtaDownloadFailure.INVALID_FIRMWARE_SIZE, selected.reason)
        assertEquals(49L, selected.observedSize)
    }

    @Test fun rejects_missing_download_url() {
        val response = otaResponse(
            manualUrl = "",
            autoUrl = "",
            size = TWO_GIB.toString(),
        )

        val selected = RealmeOtaDownloadSelector.select(response)

        assertTrue(selected is RealmeOtaDownloadSelection.Failure)
        selected as RealmeOtaDownloadSelection.Failure
        assertEquals(RealmeOtaDownloadFailure.NO_DOWNLOAD_URL, selected.reason)
    }

    @Test fun rejects_gka_builds_even_with_a_valid_packet() {
        val response = otaResponse(
            manualUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=manual",
            autoUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            size = TWO_GIB.toString(),
            gkaReq = 1,
        )

        val selected = RealmeOtaDownloadSelector.select(response)

        assertTrue(selected is RealmeOtaDownloadSelection.Failure)
        selected as RealmeOtaDownloadSelection.Failure
        assertEquals(RealmeOtaDownloadFailure.GKA_ATTESTATION_REQUIRED, selected.reason)
    }

    @Test fun ocs_builds_gka_zero_are_selected_normally() {
        val response = otaResponse(
            manualUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=manual",
            autoUrl = "https://component-ota-cn.allawntech.com/downloadCheck?tr=auto",
            size = TWO_GIB.toString(),
            gkaReq = 0,
        )

        assertTrue(RealmeOtaDownloadSelector.select(response) is RealmeOtaDownloadSelection.Success)
    }

    private fun otaResponse(
        manualUrl: String?,
        autoUrl: String,
        size: String,
        md5: String = "",
        gkaReq: Int? = null,
    ): OtaResponseDto = OtaResponseDto(
        realOtaVersion = "PKJ110_11.C.65_1650_202604091920",
        versionName = "PKJ110_16.0.5.702(CN01)",
        securityPatch = "2026-03-01",
        gkaReq = gkaReq,
        components = listOf(
            Component(
                componentName = "my_manifest",
                componentPackets = ComponentPacket(
                    url = autoUrl,
                    manualUrl = manualUrl,
                    md5 = md5,
                    size = size,
                ),
            ),
        ),
    )

    private companion object {
        private const val TWO_GIB = 2L * 1024L * 1024L * 1024L
    }
}
